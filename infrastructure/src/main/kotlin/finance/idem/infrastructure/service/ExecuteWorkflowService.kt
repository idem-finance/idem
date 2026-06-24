package finance.idem.infrastructure.service

import finance.idem.application.agentic.ExecuteWorkflowCommand
import finance.idem.application.agentic.ExecuteWorkflowUseCase
import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.application.port.AgentAuditRepository
import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.AgentAuditEvent
import finance.idem.core.agentic.LedgerIntent
import finance.idem.core.agentic.LedgerIntentLine
import finance.idem.core.agentic.PolicyEvaluationResult
import finance.idem.core.agentic.PolicyGuard
import finance.idem.core.agentic.PolicyViolationException
import finance.idem.core.agentic.WorkflowPlan
import finance.idem.core.agentic.WorkflowPlanRepository
import finance.idem.core.agentic.WorkflowStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
@Transactional
class ExecuteWorkflowService(
    private val workflowPlanRepository: WorkflowPlanRepository,
    private val agentAuditRepository: AgentAuditRepository,
    private val webhookOutboxRepository: WebhookOutboxRepository,
    private val postTransactionUseCase: PostTransactionUseCase,
) : ExecuteWorkflowUseCase {

    override fun execute(cmd: ExecuteWorkflowCommand): Result<WorkflowPlanId> {
        // SECURITY: cmd.policyRules defaults to emptyList(). PolicyGuard.evaluate() returns Approved
        // unconditionally when the list is empty — there is no default policy fallback. Callers (MCP
        // tools, future REST controllers) MUST populate policyRules from the tenant's configured rule
        // set before invoking this use case.
        //
        // TODO: pre-compute priorSessionDebitTotal and priorHourlyDebitTotal from
        //  historical journal data before constructing LedgerIntent so that
        //  MaxDebitPerSession and MaxDebitPerHour rules evaluate cumulative totals,
        //  not just this workflow's lines. Tracked in issue #200.
        val ledgerIntent = LedgerIntent(
            lines = cmd.steps.flatMap { step ->
                step.lines.map { line ->
                    LedgerIntentLine(
                        accountId = line.accountId,
                        entryType = line.entryType,
                        monetaryEntry = line.monetaryEntry,
                    )
                }
            }
        )

        val policyResult = PolicyGuard.evaluate(cmd.agentContext, ledgerIntent, cmd.policyRules)
        if (policyResult is PolicyEvaluationResult.Denied) {
            throw PolicyViolationException(policyResult.violations)
        }

        val now = Instant.now()
        val planId = WorkflowPlanId.generate()

        var plan = WorkflowPlan.create(
            id = planId,
            tenantId = cmd.tenantId,
            agentContext = cmd.agentContext,
            stepDescriptions = cmd.steps.map { it.description.ifBlank { it.idempotencyKey } },
            createdAt = now,
        )
        workflowPlanRepository.insert(plan)

        agentAuditRepository.save(
            AgentAuditEvent.pending(
                workflowPlanId = planId,
                tenantId = cmd.tenantId,
                agentContext = cmd.agentContext,
                intent = cmd.agentContext.intent,
            )
        )

        workflowPlanRepository.updateStatus(planId, cmd.tenantId, WorkflowStatus.EXECUTING)

        cmd.steps.forEachIndexed { index, step ->
            val txCmd = PostTransactionCommand(
                tenantId = cmd.tenantId,
                idempotencyKey = step.idempotencyKey,
                lines = step.lines,
                createdBy = cmd.createdBy,
                agentContext = cmd.agentContext.copy(workflowPlanId = planId),
                metadata = step.metadata,
            )
            val txResult = postTransactionUseCase.execute(txCmd)
            txResult.fold(
                onSuccess = { txId ->
                    plan = plan.withStepExecuted(index, txId)
                    workflowPlanRepository.updateStep(planId, cmd.tenantId, plan.steps[index])
                },
                onFailure = { ex ->
                    plan = plan.withStepFailed(index).withStatus(WorkflowStatus.FAILED)
                    workflowPlanRepository.updateStep(planId, cmd.tenantId, plan.steps[index])
                    workflowPlanRepository.updateStatus(planId, cmd.tenantId, WorkflowStatus.FAILED)
                    agentAuditRepository.save(
                        AgentAuditEvent.failed(
                            workflowPlanId = planId,
                            tenantId = cmd.tenantId,
                            agentContext = cmd.agentContext,
                            outcome = "Step $index failed: ${ex.message}",
                        )
                    )
                    throw RuntimeException("Workflow step $index failed: ${ex.message}", ex)
                }
            )
        }

        val completedAt = Instant.now()
        val committedPlan = plan
            .withStatus(WorkflowStatus.COMMITTED)
            .copy(completedAt = completedAt)
        workflowPlanRepository.updateStatus(planId, cmd.tenantId, WorkflowStatus.COMMITTED, completedAt = completedAt)

        agentAuditRepository.save(
            AgentAuditEvent.completed(
                workflowPlanId = planId,
                tenantId = cmd.tenantId,
                agentContext = cmd.agentContext,
                outcome = "Workflow committed with ${cmd.steps.size} step(s)",
            )
        )

        webhookOutboxRepository.save(WebhookOutboxEntry.workflowCommitted(committedPlan))

        return Result.success(planId)
    }
}
