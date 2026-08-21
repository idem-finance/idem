package finance.idem.infrastructure.service

import finance.idem.application.ledger.JournalLineRequest
import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.application.port.IdempotencyStore
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.application.reconciliation.ReorgReversalCommand
import finance.idem.application.reconciliation.ReorgReversalResult
import finance.idem.application.reconciliation.ReorgReversalUseCase
import finance.idem.core.EntryType
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.SettlementRepository
import finance.idem.core.ledger.TransactionRepository
import finance.idem.infrastructure.chain.ChainIdempotencyKey
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Reverses a chain reorg's effect on the ledger. Mirrors [RollbackWorkflowService]'s saga
 * pattern exactly: the original transaction's lines are never mutated — a new compensating
 * transaction with DEBIT/CREDIT swapped is posted through [PostTransactionUseCase] directly,
 * bypassing PolicyGuard by design (this is a system-integrity correction, not an
 * agent-initiated business transaction, and must always be able to complete regardless of the
 * tenant's configured policy rules).
 *
 * Called from two places: [AlchemyWebhookService]'s `removed:true` fast path, and
 * `SettlementFinalityPoller`'s active on-chain re-verification (the reliable backstop when a
 * webhook delivery is missed).
 */
@Service
@Transactional
class ReorgReversalService(
    private val settlementRepository: SettlementRepository,
    private val transactionRepository: TransactionRepository,
    private val postTransactionUseCase: PostTransactionUseCase,
    private val webhookOutboxRepository: WebhookOutboxRepository,
    private val idempotencyStore: IdempotencyStore,
) : ReorgReversalUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun execute(cmd: ReorgReversalCommand): Result<ReorgReversalResult> {
        val settlement =
            settlementRepository.findReversibleByTxHashAndLogIndex(cmd.tenantId, cmd.txHash, cmd.logIndex)
                ?: return Result.success(ReorgReversalResult.NoMatchingSettlement)

        // findReversibleByTxHashAndLogIndex already excludes REORGED rows, but a duplicate
        // delivery racing an earlier reversal in flight is handled defensively.
        if (settlement.status == EntryStatus.REORGED) {
            return Result.success(ReorgReversalResult.AlreadyReorged)
        }

        val originalTxId =
            checkNotNull(settlement.matchedTransactionId) {
                "Reversible settlement ${settlement.id} has null matchedTransactionId — data integrity violation"
            }
        val originalTx =
            transactionRepository.findById(originalTxId, cmd.tenantId)
                ?: return Result.failure(
                    IllegalStateException(
                        "Original transaction ${originalTxId.value} not found for reorg reversal of settlement ${settlement.id}",
                    ),
                )

        val compensatingLines =
            originalTx.lines.map { line ->
                JournalLineRequest(
                    accountId = line.accountId,
                    entryType =
                        when (line.entryType) {
                            EntryType.DEBIT -> EntryType.CREDIT
                            EntryType.CREDIT -> EntryType.DEBIT
                        },
                    monetaryEntry = line.monetaryEntry,
                    description = "Compensating entry for chain-reorg reversal of ${originalTxId.value} (${cmd.reason})",
                )
            }

        // Posted via PostTransactionUseCase directly (not ExecuteWorkflowUseCase) — intentionally
        // bypasses PolicyGuard, mirroring RollbackWorkflowService: this is a system-initiated
        // integrity correction, not an agent business transaction.
        val compensatingCmd =
            PostTransactionCommand(
                tenantId = cmd.tenantId,
                idempotencyKey = "reorg-reversal:${originalTxId.value}",
                lines = compensatingLines,
                createdBy = "chain-reorg-reversal",
                metadata = mapOf("compensating_for" to originalTxId.value.toString(), "reason" to cmd.reason),
            )
        val compensatingTxId =
            postTransactionUseCase.execute(compensatingCmd).getOrElse { ex ->
                return Result.failure(
                    RuntimeException(
                        "Failed to post reorg-reversal compensating transaction for settlement ${settlement.id}: ${ex.message}",
                        ex,
                    ),
                )
            }

        val reorgedAt = Instant.now()
        // Conditional update, not a plain save: the webhook fast path and the poller backstop
        // can both reach this point for the same settlement before either commits. Only the
        // caller that actually performs the REORGED transition proceeds to write the outbox
        // entry — the other treats it as AlreadyReorged, so exactly one settlement.reorged
        // notification is ever emitted per reversal.
        if (!settlementRepository.markReorged(settlement.id, cmd.tenantId, compensatingTxId, reorgedAt)) {
            return Result.success(ReorgReversalResult.AlreadyReorged)
        }
        val reversed = settlement.copy(status = EntryStatus.REORGED, reversalTransactionId = compensatingTxId, reorgedAt = reorgedAt)

        // Release the original posting's idempotency key so a legitimate re-confirmation
        // (the transfer re-mined with the same hash+logIndex in a later block) is not silently
        // swallowed as a "duplicate" of the transaction that was just reversed. A stale
        // at-least-once webhook redelivery of the exact reversed evidence is separately guarded
        // against in AlchemyWebhookService.isStaleReorgedReplay via the REORGED row's blockNumber.
        idempotencyStore.release(ChainIdempotencyKey.of(cmd.chainKey, cmd.txHash, cmd.logIndex), cmd.tenantId)

        webhookOutboxRepository.save(WebhookOutboxEntry.settlementReorged(reversed))

        log.warn(
            "Chain reorg reversal: settlement={} originalTx={} compensatingTx={} tenant={} txHash={} logIndex={}",
            settlement.id,
            originalTxId.value,
            compensatingTxId.value,
            cmd.tenantId.value,
            cmd.txHash,
            cmd.logIndex,
        )

        return Result.success(ReorgReversalResult.Reversed(reversed, compensatingTxId))
    }
}
