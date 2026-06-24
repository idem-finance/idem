package finance.idem.infrastructure.service

import finance.idem.application.agentic.RollbackWorkflowCommand
import finance.idem.application.agentic.RollbackWorkflowUseCase
import finance.idem.application.agentic.WorkflowPlanNotFound
import finance.idem.application.ledger.JournalLineRequest
import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.application.port.AgentAuditRepository
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.core.EntryType
import finance.idem.core.agentic.AgentAuditEvent
import finance.idem.core.agentic.WorkflowPlanRepository
import finance.idem.core.agentic.WorkflowStatus
import finance.idem.core.ledger.TransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
@Transactional
class RollbackWorkflowService(
    private val workflowPlanRepository: WorkflowPlanRepository,
    private val agentAuditRepository: AgentAuditRepository,
    private val webhookOutboxRepository: WebhookOutboxRepository,
    private val transactionRepository: TransactionRepository,
    private val postTransactionUseCase: PostTransactionUseCase,
) : RollbackWorkflowUseCase {

    override fun execute(cmd: RollbackWorkflowCommand): Result<Unit> {
        val plan = workflowPlanRepository.findById(cmd.workflowPlanId, cmd.tenantId)
            ?: return Result.failure(WorkflowPlanNotFound(cmd.workflowPlanId))

        if (plan.status !in setOf(WorkflowStatus.COMMITTED, WorkflowStatus.EXECUTING)) {
            return Result.failure(
                IllegalStateException(
                    "Cannot rollback plan ${cmd.workflowPlanId.value}: expected COMMITTED or EXECUTING, was ${plan.status}"
                )
            )
        }

        agentAuditRepository.save(
            AgentAuditEvent.pending(
                workflowPlanId = cmd.workflowPlanId,
                tenantId = cmd.tenantId,
                agentContext = cmd.agentContext.copy(intent = "ROLLBACK"),
                intent = "ROLLBACK",
            )
        )

        // Compensating transactions bypass PolicyGuard by design — rollback must always succeed
        // regardless of the current policy configuration (e.g. AllowedTokens, RequireHumanApproval).
        var updatedPlan = plan
        plan.executedSteps().sortedByDescending { it.stepOrder }.forEach { step ->
            val originalTxId = step.transactionId ?: return@forEach
            val originalTx = transactionRepository.findById(originalTxId, cmd.tenantId) ?: return@forEach

            val compensatingLines = originalTx.lines.map { line ->
                JournalLineRequest(
                    accountId = line.accountId,
                    entryType = when (line.entryType) {
                        EntryType.DEBIT -> EntryType.CREDIT
                        EntryType.CREDIT -> EntryType.DEBIT
                    },
                    monetaryEntry = line.monetaryEntry,
                    description = "Compensating entry for rollback of ${originalTxId.value}",
                )
            }

            val compensatingCmd = PostTransactionCommand(
                tenantId = cmd.tenantId,
                idempotencyKey = "rollback:${originalTxId.value}",
                lines = compensatingLines,
                createdBy = cmd.createdBy,
                agentContext = cmd.agentContext.copy(
                    intent = "ROLLBACK",
                    workflowPlanId = cmd.workflowPlanId,
                ),
                metadata = mapOf("compensating_for" to originalTxId.value.toString()),
            )
            val compensatingTxId = postTransactionUseCase.execute(compensatingCmd).getOrElse { ex ->
                throw RuntimeException("Failed to post compensating transaction for step ${step.stepOrder}: ${ex.message}", ex)
            }
            updatedPlan = updatedPlan.withStepRolledBack(step.stepOrder, compensatingTxId)
            workflowPlanRepository.updateStep(cmd.workflowPlanId, cmd.tenantId, updatedPlan.steps[step.stepOrder])
        }

        val rolledBackAt = Instant.now()
        val rolledBackPlan = updatedPlan
            .withStatus(WorkflowStatus.ROLLED_BACK)
            .copy(rolledBackAt = rolledBackAt, rollbackReason = cmd.reason)
        workflowPlanRepository.updateStatus(
            cmd.workflowPlanId, cmd.tenantId, WorkflowStatus.ROLLED_BACK,
            rolledBackAt = rolledBackAt, rollbackReason = cmd.reason,
        )

        agentAuditRepository.save(
            AgentAuditEvent.completed(
                workflowPlanId = cmd.workflowPlanId,
                tenantId = cmd.tenantId,
                agentContext = cmd.agentContext,
                outcome = "Rollback completed. Reason: ${cmd.reason}",
            )
        )

        webhookOutboxRepository.save(WebhookOutboxEntry.workflowRolledBack(rolledBackPlan))

        return Result.success(Unit)
    }
}
