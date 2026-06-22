package finance.idem.infrastructure.service

import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.application.reconciliation.ReconcileEntriesCommand
import finance.idem.application.reconciliation.ReconcileEntriesResult
import finance.idem.application.reconciliation.ReconcileEntriesUseCase
import finance.idem.application.reconciliation.ReconciliationException
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.Settlement
import finance.idem.core.ledger.SettlementRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant

@Service
class ReconcileEntriesService(
    private val settlementRepository: SettlementRepository,
    private val webhookOutboxRepository: WebhookOutboxRepository,
    txManager: PlatformTransactionManager,
    @Value("\${idem.reconciliation.amount-tolerance-percent:0}") private val tolerancePercent: BigDecimal,
) : ReconcileEntriesUseCase {

    private val transactionTemplate = TransactionTemplate(txManager)

    override fun execute(cmd: ReconcileEntriesCommand): Result<ReconcileEntriesResult> {
        val unmatchedEntries = settlementRepository.findUnmatchedInWindow(
            tenantId = cmd.tenantId,
            accountId = cmd.accountId,
            from = cmd.from,
            to = cmd.to,
        )

        val exceptions = mutableListOf<ReconciliationException>()
        var matched = 0

        for (entry in unmatchedEntries) {
            val settled = transactionTemplate.execute { processEntry(cmd, entry, exceptions) } ?: false
            if (settled) matched++
        }

        val unmatched = unmatchedEntries.size - matched
        return Result.success(ReconcileEntriesResult(matched, unmatched, exceptions))
    }

    private fun processEntry(
        cmd: ReconcileEntriesCommand,
        entry: Settlement,
        exceptions: MutableList<ReconciliationException>,
    ): Boolean {
        val candidates = settlementRepository.findPendingCandidates(
            tenantId = cmd.tenantId,
            accountIds = setOf(entry.accountId),
            token = entry.token,
            chainId = entry.chainId,
            walletAddress = entry.walletAddress,
            since = cmd.from,
        )
        val match = findMatch(candidates, entry)
        return if (match != null) {
            settlementRepository.save(
                match.copy(
                    status = EntryStatus.SETTLED,
                    matchedTransactionId = entry.matchedTransactionId,
                    txHash = entry.txHash,
                    blockNumber = entry.blockNumber,
                    confirmedAt = Instant.now(),
                )
            )
            settlementRepository.save(entry.copy(status = EntryStatus.SETTLED))
            webhookOutboxRepository.save(WebhookOutboxEntry.transactionSettled(entry))
            log.info(
                "ReconcileEntries: settled UNMATCHED={} against PENDING={} tenant={} amount={} token={} txHash={}",
                entry.id, match.id, entry.tenantId.value, entry.amount.value, entry.token, entry.txHash,
            )
            true
        } else {
            webhookOutboxRepository.save(WebhookOutboxEntry.reconciliationException(entry))
            exceptions += ReconciliationException(
                settlementId = entry.id,
                txHash = entry.txHash,
                reason = "No matching pending settlement found",
            )
            log.warn(
                "ReconcileEntries: no PENDING match for UNMATCHED={} tenant={} amount={} token={} chainId={} wallet={}",
                entry.id, entry.tenantId.value, entry.amount.value, entry.token, entry.chainId, entry.walletAddress,
            )
            false
        }
    }

    /**
     * Matches PENDING candidates against an UNMATCHED settlement by amount, FIFO on ties.
     * Only candidates with no expectedFromAddress are eligible — the original fromAddress
     * is not stored on Settlement, so sender-confirmed (Tier 1) matching is not possible
     * in a sweep context. Configurable tolerance via idem.reconciliation.amount-tolerance-percent.
     */
    private fun findMatch(candidates: List<Settlement>, entry: Settlement): Settlement? =
        candidates
            .filter { it.expectedFromAddress == null }
            .firstOrNull { candidate ->
                if (tolerancePercent > BigDecimal.ZERO) {
                    val tolerance = entry.amount.value * tolerancePercent
                    (candidate.amount.value - entry.amount.value).abs() <= tolerance
                } else {
                    candidate.amount == entry.amount
                }
            }

    companion object {
        private val log = LoggerFactory.getLogger(ReconcileEntriesService::class.java)
    }
}
