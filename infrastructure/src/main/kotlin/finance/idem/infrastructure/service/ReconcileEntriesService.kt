package finance.idem.infrastructure.service

import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.application.reconciliation.ReconcileEntriesCommand
import finance.idem.application.reconciliation.ReconcileEntriesResult
import finance.idem.application.reconciliation.ReconcileEntriesUseCase
import finance.idem.application.reconciliation.ReconciliationException
import finance.idem.core.ChainId
import finance.idem.core.StablecoinToken
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

    private sealed class EntryOutcome {
        data class Settled(
            val settlementId: String,
        ) : EntryOutcome()

        data class Unmatched(
            val exception: ReconciliationException,
        ) : EntryOutcome()

        data class Failed(
            val exception: ReconciliationException,
        ) : EntryOutcome()
    }

    private data class GroupKey(
        val token: StablecoinToken,
        val chainId: ChainId,
        val walletAddress: String,
    )

    override fun execute(cmd: ReconcileEntriesCommand): Result<ReconcileEntriesResult> {
        val unmatchedEntries =
            settlementRepository.findUnmatchedInWindow(
                tenantId = cmd.tenantId,
                accountId = cmd.accountId,
                from = cmd.from,
                to = cmd.to,
            )

        val effectiveTolerance = cmd.tolerancePercent ?: tolerancePercent
        require(effectiveTolerance.compareTo(BigDecimal.ZERO) >= 0 && effectiveTolerance.compareTo(BigDecimal("100")) <= 0) {
            "tolerancePercent must be in [0, 100], got $effectiveTolerance"
        }
        val outcomes = mutableListOf<EntryOutcome>()
        val grouped = unmatchedEntries.groupBy { GroupKey(it.token, it.chainId, it.walletAddress) }

        for ((key, entries) in grouped) {
            try {
                val groupOutcomes =
                    transactionTemplate.execute { processGroup(cmd, key, entries, effectiveTolerance) }
                        ?: entries.map { EntryOutcome.Failed(ReconciliationException(it.id, it.txHash, "transaction aborted")) }
                outcomes += groupOutcomes
            } catch (e: Exception) {
                log.error(
                    "ReconcileEntries: group failed token={} chainId={} wallet={} tenant={} — {}",
                    key.token,
                    key.chainId,
                    key.walletAddress,
                    cmd.tenantId.value,
                    e.message,
                    e,
                )
                entries.forEach { entry ->
                    outcomes +=
                        EntryOutcome.Failed(
                            ReconciliationException(entry.id, entry.txHash, e.message ?: "unexpected error"),
                        )
                }
            }
        }

        val matched = outcomes.count { it is EntryOutcome.Settled }
        val settlementIds = outcomes.filterIsInstance<EntryOutcome.Settled>().map { it.settlementId }
        val exceptions =
            outcomes.mapNotNull {
                when (it) {
                    is EntryOutcome.Settled -> null
                    is EntryOutcome.Unmatched -> it.exception
                    is EntryOutcome.Failed -> it.exception
                }
            }
        val unmatched = unmatchedEntries.size - matched
        return Result.success(ReconcileEntriesResult(matched, unmatched, exceptions, settlementIds))
    }

    private fun processGroup(
        cmd: ReconcileEntriesCommand,
        key: GroupKey,
        entries: List<Settlement>,
        effectiveTolerance: BigDecimal,
    ): List<EntryOutcome> {
        val candidates =
            settlementRepository
                .findPendingCandidates(
                    tenantId = cmd.tenantId,
                    accountIds = entries.map { it.accountId }.toSet(),
                    token = key.token,
                    chainId = key.chainId,
                    walletAddress = key.walletAddress,
                    since = cmd.from,
                ).toMutableList()

        return entries.map { entry -> settleEntry(entry, candidates, effectiveTolerance) }
    }

    private fun settleEntry(
        entry: Settlement,
        candidates: MutableList<Settlement>,
        effectiveTolerance: BigDecimal,
    ): EntryOutcome {
        val match = findMatch(candidates.filter { it.accountId == entry.accountId }, entry, effectiveTolerance)
        return if (match != null) {
            candidates.remove(match)
            // Mirrors BasicReconciliationService.settle()'s isWebhookSourced gate: entry.confirmedAt
            // is the shared "already past finality" signal (set immediately for non-webhook-sourced
            // rows, left null for webhook-sourced rows until SettlementFinalityPoller confirms it) —
            // not entry.createdBy, which createUnmatched always sets to "system" regardless of source.
            // A manual reconcile_batch sweep must not bypass the finality gate the automatic path enforces.
            val stillPendingFinality = entry.confirmedAt == null
            val newStatus = if (stillPendingFinality) EntryStatus.WATCHING else EntryStatus.SETTLED
            val confirmedAt = if (stillPendingFinality) null else Instant.now()
            settlementRepository.save(
                match.copy(
                    status = newStatus,
                    matchedTransactionId = entry.matchedTransactionId,
                    txHash = entry.txHash,
                    blockNumber = entry.blockNumber,
                    confirmedAt = confirmedAt,
                    // Needed for SettlementFinalityPoller.verifyLogStillPresent when this row is
                    // WATCHING — previously never set here because this path always short-circuited
                    // straight to SETTLED, before either field mattered.
                    chainKey = entry.chainKey,
                    logIndex = entry.logIndex,
                ),
            )
            settlementRepository.save(entry.copy(status = newStatus, confirmedAt = confirmedAt))
            // WATCHING settlements are not yet finality-confirmed — SettlementFinalityPoller fires
            // transaction.settled once it promotes both rows to SETTLED on a later sweep.
            if (!stillPendingFinality) {
                webhookOutboxRepository.save(WebhookOutboxEntry.transactionSettled(entry))
            }
            log.info(
                "ReconcileEntries: {} UNMATCHED={} against PENDING={} tenant={} amount={} token={} txHash={}",
                if (stillPendingFinality) "matched (pending finality)" else "settled",
                entry.id,
                match.id,
                entry.tenantId.value,
                entry.amount.value,
                entry.token,
                entry.txHash,
            )
            EntryOutcome.Settled(entry.id.toString())
        } else {
            webhookOutboxRepository.save(WebhookOutboxEntry.reconciliationException(entry))
            val exception =
                ReconciliationException(
                    settlementId = entry.id,
                    txHash = entry.txHash,
                    reason = "No matching pending settlement found",
                )
            log.warn(
                "ReconcileEntries: no PENDING match for UNMATCHED={} tenant={} amount={} token={} chainId={} wallet={}",
                entry.id,
                entry.tenantId.value,
                entry.amount.value,
                entry.token,
                entry.chainId,
                entry.walletAddress,
            )
            EntryOutcome.Unmatched(exception)
        }
    }

    /**
     * Matches PENDING candidates against an UNMATCHED settlement by amount, FIFO on ties.
     * Only candidates with no expectedFromAddress are eligible — the original fromAddress
     * is not stored on Settlement, so sender-confirmed (Tier 1) matching is not possible
     * in a sweep context. Configurable tolerance via idem.reconciliation.amount-tolerance-percent,
     * overridable per-call via ReconcileEntriesCommand.tolerancePercent.
     */
    private fun findMatch(
        candidates: List<Settlement>,
        entry: Settlement,
        effectiveTolerance: BigDecimal,
    ): Settlement? =
        candidates
            .filter { it.expectedFromAddress == null }
            .firstOrNull { candidate ->
                if (effectiveTolerance > BigDecimal.ZERO) {
                    val tolerance = entry.amount.value * effectiveTolerance / BigDecimal("100")
                    (candidate.amount.value - entry.amount.value).abs() <= tolerance
                } else {
                    candidate.amount == entry.amount
                }
            }

    companion object {
        private val log = LoggerFactory.getLogger(ReconcileEntriesService::class.java)
    }
}
