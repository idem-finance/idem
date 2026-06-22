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
import finance.idem.application.port.WorkflowPlanRepository
import finance.idem.core.EntryType
import finance.idem.core.agentic.AgentAuditEvent
import finance.idem.core.agentic.WorkflowPlanStatus
import finance.idem.core.ledger.TransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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

        agentAuditRepository.save(
            AgentAuditEvent.pending(
                workflowPlanId = cmd.workflowPlanId,
                tenantId = cmd.tenantId,
                agentContext = cmd.agentContext.copy(intent = "ROLLBACK"),
                intent = "ROLLBACK",
            )
        )

        plan.executedSteps().sortedByDescending { it.stepIndex }.forEach { step ->
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
            )
            postTransactionUseCase.execute(compensatingCmd).getOrElse { ex ->
                throw RuntimeException("Failed to post compensating transaction for step ${step.stepIndex}: ${ex.message}", ex)
            }
        }

        val rolledBackPlan = plan.withStatus(WorkflowPlanStatus.ROLLED_BACK)
        workflowPlanRepository.save(rolledBackPlan)

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
