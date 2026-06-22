package finance.idem.infrastructure.service

import finance.idem.application.agentic.ExecuteWorkflowCommand
import finance.idem.application.agentic.ExecuteWorkflowUseCase
import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.application.port.AgentAuditRepository
import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.application.port.WorkflowPlanRepository
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.AgentAuditEvent
import finance.idem.core.agentic.LedgerIntent
import finance.idem.core.agentic.LedgerIntentLine
import finance.idem.core.agentic.PolicyEvaluationResult
import finance.idem.core.agentic.PolicyGuard
import finance.idem.core.agentic.PolicyViolationException
import finance.idem.core.agentic.WorkflowPlan
import finance.idem.core.agentic.WorkflowPlanStatus
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
        //  not just this workflow's lines. Tracked in issue #TODO.
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
            stepIdempotencyKeys = cmd.steps.map { it.idempotencyKey },
            occurredAt = now,
        )
        workflowPlanRepository.save(plan)

        agentAuditRepository.save(
            AgentAuditEvent.pending(
                workflowPlanId = planId,
                tenantId = cmd.tenantId,
                agentContext = cmd.agentContext,
                intent = cmd.agentContext.intent,
            )
        )

        plan = plan.withStatus(WorkflowPlanStatus.EXECUTING)
        workflowPlanRepository.save(plan)

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
                    workflowPlanRepository.save(plan)
                },
                onFailure = { ex ->
                    plan = plan.withStepFailed(index).withStatus(WorkflowPlanStatus.ROLLED_BACK)
                    workflowPlanRepository.save(plan)
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

        val committedPlan = plan
            .withStatus(WorkflowPlanStatus.COMMITTED)
            .copy(committedAt = Instant.now())
        workflowPlanRepository.save(committedPlan)

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
