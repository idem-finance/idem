package finance.idem.infrastructure.service

import finance.idem.application.ledger.JournalLineRequest
import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.application.port.AgentAuditRepository
import finance.idem.application.port.IdempotencyStore
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.application.reconciliation.ReorgReversalCommand
import finance.idem.application.reconciliation.ReorgReversalResult
import finance.idem.application.reconciliation.ReorgReversalUseCase
import finance.idem.core.EntryType
import finance.idem.core.TransactionId
import finance.idem.core.agentic.AgentAuditEvent
import finance.idem.core.agentic.StepStatus
import finance.idem.core.agentic.WorkflowPlanRepository
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.Settlement
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
 *
 * Mutual exclusion with [RollbackWorkflowService]: both services are independent sagas that can
 * target the same original transaction (an agent-executed workflow step whose settlement later
 * reorgs, or is separately rolled back by an operator). Both tag their compensating
 * [PostTransactionCommand] with a deterministic idempotency key derived from the original
 * transaction id — `"rollback:<id>"` vs `"reorg-reversal:<id>"` — so before posting a new
 * compensation this service checks whether a rollback compensation already exists for this
 * transaction and, if so, reuses it instead of double-compensating.
 */
@Service
@Transactional
class ReorgReversalService(
    private val settlementRepository: SettlementRepository,
    private val transactionRepository: TransactionRepository,
    private val postTransactionUseCase: PostTransactionUseCase,
    private val webhookOutboxRepository: WebhookOutboxRepository,
    private val idempotencyStore: IdempotencyStore,
    private val workflowPlanRepository: WorkflowPlanRepository,
    private val agentAuditRepository: AgentAuditRepository,
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

        // An operator/agent-initiated rollback (RollbackWorkflowService) may have already
        // compensated this exact transaction — same original tx, different idempotency key.
        // Reuse its compensating transaction instead of posting a second one.
        val existingRollbackTx = transactionRepository.findByIdempotencyKey("rollback:${originalTxId.value}", cmd.tenantId)
        if (existingRollbackTx != null) {
            return alreadyCompensatedByRollback(cmd, settlement, originalTxId, existingRollbackTx.id)
        }

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

        linkToOriginatingWorkflow(cmd, originalTxId, compensatingTxId)

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

    /**
     * The original transaction was already compensated by [RollbackWorkflowService] — the
     * ledger is already balanced, so no new compensating transaction is posted. The settlement
     * is still flagged REORGED (reusing the rollback's compensating transaction id as
     * reversalTransactionId) so the reorg remains visible to operators/compliance, and — if the
     * transaction was agent-originated — an audit event is still recorded for traceability.
     */
    private fun alreadyCompensatedByRollback(
        cmd: ReorgReversalCommand,
        settlement: Settlement,
        originalTxId: TransactionId,
        rollbackTxId: TransactionId,
    ): Result<ReorgReversalResult> {
        val reorgedAt = Instant.now()
        if (!settlementRepository.markReorged(settlement.id, cmd.tenantId, rollbackTxId, reorgedAt)) {
            return Result.success(ReorgReversalResult.AlreadyReorged)
        }
        val reversed = settlement.copy(status = EntryStatus.REORGED, reversalTransactionId = rollbackTxId, reorgedAt = reorgedAt)
        idempotencyStore.release(ChainIdempotencyKey.of(cmd.chainKey, cmd.txHash, cmd.logIndex), cmd.tenantId)
        webhookOutboxRepository.save(WebhookOutboxEntry.settlementReorged(reversed))

        workflowPlanRepository.findByTransactionId(originalTxId, cmd.tenantId)?.let { plan ->
            agentAuditRepository.save(
                AgentAuditEvent.completed(
                    workflowPlanId = plan.id,
                    tenantId = cmd.tenantId,
                    agentContext = plan.agentContext.copy(intent = "CHAIN_REORG_REVERSAL"),
                    outcome =
                        "Chain reorg detected for already-rolled-back transaction ${originalTxId.value} — " +
                            "no new compensation needed, ledger already balanced by rollback tx ${rollbackTxId.value}. " +
                            "Reason: ${cmd.reason}",
                ),
            )
        }

        log.warn(
            "Chain reorg reversal: settlement={} originalTx={} already compensated by rollback tx={} tenant={} txHash={} logIndex={}",
            settlement.id,
            originalTxId.value,
            rollbackTxId.value,
            cmd.tenantId.value,
            cmd.txHash,
            cmd.logIndex,
        )
        return Result.success(ReorgReversalResult.AlreadyCompensatedByRollback(reversed, rollbackTxId))
    }

    /**
     * Links a reorg reversal back to its originating agent workflow, if any — most on-chain
     * transactions aren't agent-originated, so a null lookup (the common case) is a silent no-op.
     * When found, the step is marked REORGED (distinct from ROLLED_BACK) and an audit event is
     * written so the reversal surfaces through get_agent_audit_log.
     */
    private fun linkToOriginatingWorkflow(
        cmd: ReorgReversalCommand,
        originalTxId: TransactionId,
        compensatingTxId: TransactionId,
    ) {
        val plan = workflowPlanRepository.findByTransactionId(originalTxId, cmd.tenantId) ?: return
        // Status filter is defensive: a concurrent rollback could in theory have moved this step
        // off EXECUTED between our earlier idempotency-key check and here. The rollback-key check
        // above is the actual concurrency-safe guard (backed by the idempotency store); this is
        // just "don't stomp a status transition that isn't ours to make."
        val step =
            plan.steps.firstOrNull { it.transactionId == originalTxId && it.status == StepStatus.EXECUTED }
                ?: return

        val updatedPlan = plan.withStepReorged(step.stepOrder, compensatingTxId)
        workflowPlanRepository.updateStep(plan.id, cmd.tenantId, updatedPlan.steps[step.stepOrder])

        agentAuditRepository.save(
            AgentAuditEvent.completed(
                workflowPlanId = plan.id,
                tenantId = cmd.tenantId,
                agentContext = plan.agentContext.copy(intent = "CHAIN_REORG_REVERSAL"),
                outcome =
                    "Chain reorg reversed step ${step.stepOrder}'s settlement (originalTx=${originalTxId.value}). " +
                        "Reason: ${cmd.reason}. Compensating tx: ${compensatingTxId.value}",
            ),
        )
    }
}
