package finance.idem.infrastructure.service

import finance.idem.application.agentic.CompensatedStepSummary
import finance.idem.application.agentic.RollbackWorkflowCommand
import finance.idem.application.agentic.RollbackWorkflowSummary
import finance.idem.application.agentic.RollbackWorkflowUseCase
import finance.idem.application.agentic.WorkflowPlanNotFound
import finance.idem.application.ledger.JournalLineRequest
import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.core.EntryType
import finance.idem.core.agentic.AgentAuditEvent
import finance.idem.core.agentic.WorkflowPlanRepository
import finance.idem.core.agentic.WorkflowStatus
import finance.idem.core.ledger.TransactionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
@Transactional
class RollbackWorkflowService(
    private val workflowPlanRepository: WorkflowPlanRepository,
    private val agentAuditRecorder: AgentAuditRecorder,
    private val webhookOutboxRepository: WebhookOutboxRepository,
    private val transactionRepository: TransactionRepository,
    private val postTransactionUseCase: PostTransactionUseCase,
) : RollbackWorkflowUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun execute(cmd: RollbackWorkflowCommand): Result<RollbackWorkflowSummary> {
        val plan =
            workflowPlanRepository.findById(cmd.workflowPlanId, cmd.tenantId)
                ?: return Result.failure(WorkflowPlanNotFound(cmd.workflowPlanId))

        if (plan.status !in setOf(WorkflowStatus.COMMITTED, WorkflowStatus.EXECUTING)) {
            return Result.failure(
                IllegalStateException(
                    "Cannot rollback plan ${cmd.workflowPlanId.value}: expected COMMITTED or EXECUTING, was ${plan.status}",
                ),
            )
        }

        // Durable "rollback started" record — survives a rollback of this business tx
        // (e.g. if a compensating transaction below fails and the whole rollback aborts).
        // Must fail closed: if this can't be persisted, the rollback must not proceed un-audited.
        recordDurableOrThrow(
            AgentAuditEvent.pending(
                workflowPlanId = cmd.workflowPlanId,
                tenantId = cmd.tenantId,
                agentContext = cmd.agentContext.copy(intent = "ROLLBACK"),
                intent = "ROLLBACK",
            ),
        )

        // Compensating transactions bypass PolicyGuard by design — rollback must always succeed
        // regardless of the current policy configuration (e.g. AllowedTokens, RequireHumanApproval).
        var updatedPlan = plan
        plan.executedSteps().sortedByDescending { it.stepOrder }.forEach { step ->
            val originalTxId =
                checkNotNull(step.transactionId) {
                    "Executed step ${step.stepOrder} of plan ${cmd.workflowPlanId.value} has null transactionId — data integrity violation"
                }
            val originalTx =
                transactionRepository.findById(originalTxId, cmd.tenantId)
                    ?: error("Original transaction $originalTxId not found for step ${step.stepOrder} — cannot compensate")

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
                        description = "Compensating entry for rollback of ${originalTxId.value}",
                    )
                }

            val compensatingCmd =
                PostTransactionCommand(
                    tenantId = cmd.tenantId,
                    idempotencyKey = "rollback:${originalTxId.value}",
                    lines = compensatingLines,
                    createdBy = cmd.createdBy,
                    agentContext =
                        cmd.agentContext.copy(
                            intent = "ROLLBACK",
                            workflowPlanId = cmd.workflowPlanId,
                        ),
                    metadata = mapOf("compensating_for" to originalTxId.value.toString()),
                )
            val compensatingTxId =
                postTransactionUseCase.execute(compensatingCmd).getOrElse { ex ->
                    // Durable failure record — the throw rolls back the entire rollback
                    // transaction (all compensations), so this must commit independently.
                    recordDurableSwallowing(
                        AgentAuditEvent.failed(
                            workflowPlanId = cmd.workflowPlanId,
                            tenantId = cmd.tenantId,
                            agentContext = cmd.agentContext.copy(intent = "ROLLBACK"),
                            outcome = "Rollback failed: compensating transaction for step ${step.stepOrder} failed: ${ex.message}",
                        ),
                    )
                    throw RuntimeException("Failed to post compensating transaction for step ${step.stepOrder}: ${ex.message}", ex)
                }
            updatedPlan = updatedPlan.withStepRolledBack(step.stepOrder, compensatingTxId)
            workflowPlanRepository.updateStep(cmd.workflowPlanId, cmd.tenantId, updatedPlan.steps[step.stepOrder])
        }

        val rolledBackAt = Instant.now()
        val rolledBackPlan =
            updatedPlan
                .withStatus(WorkflowStatus.ROLLED_BACK)
                .copy(rolledBackAt = rolledBackAt, rollbackReason = cmd.reason)
        workflowPlanRepository.updateStatus(
            cmd.workflowPlanId,
            cmd.tenantId,
            WorkflowStatus.ROLLED_BACK,
            rolledBackAt = rolledBackAt,
            rollbackReason = cmd.reason,
        )

        // Durable completion record, own transaction — a failure here must not roll back
        // a rollback status transition that already reflects already-executed compensations.
        recordDurableSwallowing(
            AgentAuditEvent.completed(
                workflowPlanId = cmd.workflowPlanId,
                tenantId = cmd.tenantId,
                agentContext = cmd.agentContext.copy(intent = "ROLLBACK"),
                outcome = "Rollback completed. Reason: ${cmd.reason}",
            ),
        )

        webhookOutboxRepository.save(WebhookOutboxEntry.workflowRolledBack(rolledBackPlan))

        val compensated =
            rolledBackPlan.steps
                .filter { it.compensatingTransactionId != null }
                .sortedBy { it.stepOrder }
                .map { CompensatedStepSummary(it.stepOrder, it.description, it.compensatingTransactionId) }
        return Result.success(RollbackWorkflowSummary(cmd.workflowPlanId, compensated, "ROLLED_BACK"))
    }

    /**
     * Writes an audit event in its own committed transaction via [AgentAuditRecorder].
     * Propagates on failure — used strictly pre-compensation, where nothing has happened yet
     * that would need to survive an aborted rollback request.
     */
    private fun recordDurableOrThrow(event: AgentAuditEvent) {
        agentAuditRecorder.recordDurable(event)
    }

    /**
     * Writes an audit event in its own committed transaction via [AgentAuditRecorder].
     * A failure to persist the audit event is logged but never masks the outcome the caller
     * is about to propagate — used once compensation has already happened (or is already
     * known to have failed), so aborting further would not undo anything.
     */
    private fun recordDurableSwallowing(event: AgentAuditEvent) {
        runCatching { agentAuditRecorder.recordDurable(event) }
            .onFailure { log.error("Failed to write durable agent audit event for plan {}", event.workflowPlanId.value, it) }
    }
}
