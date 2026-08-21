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
import finance.idem.infrastructure.chain.FinalityPolicy
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

        val candidates =
            settlementRepository.findPendingCandidates(
                tenantId = transaction.tenantId,
                accountIds = candidateAccountIds,
                token = onChainEntry.token,
                chainId = onChainEntry.chainId,
                walletAddress = onChainEntry.walletAddress,
                since = since,
            )

        val match = findMatch(candidates, onChainEntry)
        return if (match != null) {
            settle(match, transaction, onChainEntry)
        } else {
            createUnmatched(transaction, onChainLines, onChainEntry)
        }
    }

    /**
     * Two-tier match against amount-matching PENDING candidates (already ordered
     * `createdAt ASC`, so `firstOrNull` per tier preserves "oldest wins"):
     *
     * - Tier 1 (sender-confirmed): a candidate's [Settlement.expectedFromAddress]
     *   agrees with [OnChainEntry.fromAddress] — both non-null and equal,
     *   case-insensitively. Case-insensitive because EVM `fromAddress` is
     *   canonical lowercase hex, while Tron `fromAddress` is stored as a
     *   lowercased Base58Check string (an internal-only representation), but an
     *   operator-registered `expectedFromAddress` is expected in standard
     *   mixed-case Base58. Preferred over FIFO regardless of position.
     * - Tier 2 (amount + FIFO, today's behavior): only candidates with NO registered
     *   [Settlement.expectedFromAddress] are eligible. A candidate whose
     *   `expectedFromAddress` disagrees with `onChainEntry.fromAddress` is positive
     *   evidence this transfer is NOT that expectation, so it is excluded from both
     *   tiers rather than falling back to FIFO.
     */
    private fun findMatch(
        candidates: List<Settlement>,
        onChainEntry: OnChainEntry,
    ): Settlement? {
        val amountMatches = candidates.filter { it.amount == onChainEntry.amount }

        val tier1 =
            amountMatches.firstOrNull { candidate ->
                val expected = candidate.expectedFromAddress
                expected != null && onChainEntry.fromAddress != null &&
                    expected.equals(onChainEntry.fromAddress, ignoreCase = true)
            }
        if (tier1 != null) return tier1

        return amountMatches.firstOrNull { it.expectedFromAddress == null }
    }

    private fun settle(
        match: Settlement,
        transaction: Transaction,
        onChainEntry: OnChainEntry,
    ): ReconciliationResult {
        // Only the real-time Alchemy webhook path needs a finality gate — the chain-recovery
        // and Tron pollers only ever surface transfers already past their chain's finality
        // bound (EvmChainReader.resolveScanBound() / TronChainReader's block-time margin), so
        // they can settle immediately, as today.
        val isWebhookSourced = transaction.createdBy == FinalityPolicy.WEBHOOK_SOURCE
        val saved =
            settlementRepository.save(
                match.copy(
                    status = if (isWebhookSourced) EntryStatus.WATCHING else EntryStatus.SETTLED,
                    matchedTransactionId = transaction.id,
                    txHash = onChainEntry.txHash,
                    blockNumber = onChainEntry.blockNumber,
                    confirmedAt = if (isWebhookSourced) null else Instant.now(),
                    chainKey = transaction.metadata["chain_key"],
                    logIndex = transaction.metadata["log_index"]?.toIntOrNull(),
                ),
            )
        // WATCHING settlements are not yet finality-confirmed — SettlementFinalityPoller
        // fires transaction.settled once it promotes this row to SETTLED.
        if (!isWebhookSourced) {
            webhookOutboxRepository.save(WebhookOutboxEntry.transactionSettled(transaction))
        }
        return ReconciliationResult.Settled(saved)
    }

    private fun createUnmatched(
        transaction: Transaction,
        onChainLines: List<JournalLine>,
        onChainEntry: OnChainEntry,
    ): ReconciliationResult {
        // By convention, on-chain transactions carry exactly one DEBIT/CREDIT pair
        // sharing an identical OnChainEntry — a precondition Transaction.validate()
        // does not enforce. Bail out rather than throw if it's ever violated, so a
        // reconciliation gap can't roll back an otherwise-valid ledger commit.
        val creditLine = onChainLines.firstOrNull { it.entryType == EntryType.CREDIT }
        if (creditLine == null) {
            log.warn(
                "Reconciliation: tx={} tenant={} has on-chain lines but no CREDIT-typed line " +
                    "— skipping reconciliation",
                transaction.id.value,
                transaction.tenantId.value,
            )
            return ReconciliationResult.NotApplicable
        }
        // Mirrors settle()'s isWebhookSourced branch: a webhook-sourced UNMATCHED row is not yet
        // finality-confirmed either — SettlementFinalityPoller sweeps it the same way it sweeps
        // WATCHING rows (see findPendingFinalitySweep), and fires the deferred
        // reconciliation.unmatched webhook once it's verified past finality.
        val isWebhookSourced = transaction.createdBy == FinalityPolicy.WEBHOOK_SOURCE
        val now = Instant.now()
        val saved =
            settlementRepository.save(
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
                    confirmedAt = if (isWebhookSourced) null else now,
                    createdAt = now,
                    createdBy = "system",
                    chainKey = transaction.metadata["chain_key"],
                    logIndex = transaction.metadata["log_index"]?.toIntOrNull(),
                ),
            )
        if (!isWebhookSourced) {
            webhookOutboxRepository.save(WebhookOutboxEntry.reconciliationUnmatched(transaction))
        }
        log.warn(
            "Reconciliation: no PENDING match for tx={} tenant={} amount={} token={} chainId={} " +
                "wallet={} txHash={} — flagged UNMATCHED (id={})",
            transaction.id.value,
            transaction.tenantId.value,
            onChainEntry.amount.value,
            onChainEntry.token,
            onChainEntry.chainId,
            onChainEntry.walletAddress,
            onChainEntry.txHash,
            saved.id,
        )
        return ReconciliationResult.Unmatched(saved)
    }

    companion object {
        private val log = LoggerFactory.getLogger(BasicReconciliationService::class.java)
    }
}
