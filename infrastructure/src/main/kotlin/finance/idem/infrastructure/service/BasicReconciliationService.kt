package finance.idem.infrastructure.service

import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.application.reconciliation.BasicReconciliationUseCase
import finance.idem.application.reconciliation.ReconciliationResult
import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.JournalLine
import finance.idem.core.ledger.Settlement
import finance.idem.core.ledger.SettlementRepository
import finance.idem.core.ledger.Transaction
import finance.idem.core.monetary.OnChainEntry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class BasicReconciliationService(
    private val settlementRepository: SettlementRepository,
    private val webhookOutboxRepository: WebhookOutboxRepository,
    @Value("\${idem.reconciliation.enabled:true}") private val enabled: Boolean,
    @Value("\${idem.reconciliation.matching-window-hours:24}") private val matchingWindowHours: Long,
) : BasicReconciliationUseCase {

    override fun reconcile(transaction: Transaction): ReconciliationResult {
        if (!enabled) return ReconciliationResult.NotApplicable

        val onChainLines = transaction.lines.filter { it.monetaryEntry is OnChainEntry }
        if (onChainLines.isEmpty()) return ReconciliationResult.NotApplicable

        val onChainEntry = onChainLines.first().monetaryEntry as OnChainEntry
        val candidateAccountIds: Set<AccountId> = onChainLines.map { it.accountId }.toSet()
        val since = Instant.now().minusSeconds(matchingWindowHours * 3600)

        val candidates = settlementRepository.findPendingCandidates(
            tenantId = transaction.tenantId,
            accountIds = candidateAccountIds,
            token = onChainEntry.token,
            chainId = onChainEntry.chainId,
            walletAddress = onChainEntry.walletAddress,
            since = since,
        )

        val match = candidates.firstOrNull { it.amount == onChainEntry.amount }
        return if (match != null) {
            settle(match, transaction, onChainEntry)
        } else {
            createUnmatched(transaction, onChainLines, onChainEntry)
        }
    }

    private fun settle(match: Settlement, transaction: Transaction, onChainEntry: OnChainEntry): ReconciliationResult {
        val saved = settlementRepository.save(
            match.copy(
                status = EntryStatus.SETTLED,
                matchedTransactionId = transaction.id,
                txHash = onChainEntry.txHash,
                blockNumber = onChainEntry.blockNumber,
                confirmedAt = Instant.now(),
            )
        )
        webhookOutboxRepository.save(WebhookOutboxEntry.transactionSettled(transaction))
        return ReconciliationResult.Settled(saved)
    }

    private fun createUnmatched(
        transaction: Transaction,
        onChainLines: List<JournalLine>,
        onChainEntry: OnChainEntry,
    ): ReconciliationResult {
        val creditLine = onChainLines.first { it.entryType == EntryType.CREDIT }
        val now = Instant.now()
        val saved = settlementRepository.save(
            Settlement(
                id = UUID.randomUUID(),
                tenantId = transaction.tenantId,
                accountId = creditLine.accountId,
                amount = onChainEntry.amount,
                token = onChainEntry.token,
                chainId = onChainEntry.chainId,
                walletAddress = onChainEntry.walletAddress,
                status = EntryStatus.UNMATCHED,
                matchedTransactionId = transaction.id,
                txHash = onChainEntry.txHash,
                blockNumber = onChainEntry.blockNumber,
                confirmedAt = now,
                createdAt = now,
                createdBy = "system",
            )
        )
        webhookOutboxRepository.save(WebhookOutboxEntry.reconciliationUnmatched(transaction))
        log.warn(
            "Reconciliation: no PENDING match for tx={} tenant={} amount={} token={} chainId={} " +
                "wallet={} txHash={} — flagged UNMATCHED (id={})",
            transaction.id.value, transaction.tenantId.value, onChainEntry.amount.value,
            onChainEntry.token, onChainEntry.chainId, onChainEntry.walletAddress,
            onChainEntry.txHash, saved.id,
        )
        return ReconciliationResult.Unmatched(saved)
    }

    companion object {
        private val log = LoggerFactory.getLogger(BasicReconciliationService::class.java)
    }
}
