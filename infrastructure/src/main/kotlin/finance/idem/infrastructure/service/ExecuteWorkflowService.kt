package finance.idem.infrastructure.service

import finance.idem.application.agentic.ExecuteWorkflowCommand
import finance.idem.application.agentic.ExecuteWorkflowUseCase
import finance.idem.application.agentic.SessionDebitPort
import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.core.MonetaryAmount
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.AgentAuditEvent
import finance.idem.core.agentic.LedgerIntent
import finance.idem.core.agentic.LedgerIntentLine
import finance.idem.core.agentic.PolicyEvaluationResult
import finance.idem.core.agentic.PolicyGuard
import finance.idem.core.agentic.PolicyRepository
import finance.idem.core.agentic.PolicyRule
import finance.idem.core.agentic.PolicyViolationException
import finance.idem.core.agentic.WorkflowPlan
import finance.idem.core.agentic.WorkflowPlanRepository
import finance.idem.core.agentic.WorkflowStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
@Transactional
class ExecuteWorkflowService(
    private val workflowPlanRepository: WorkflowPlanRepository,
    private val agentAuditRecorder: AgentAuditRecorder,
    private val webhookOutboxRepository: WebhookOutboxRepository,
    private val postTransactionUseCase: PostTransactionUseCase,
    private val policyRepository: PolicyRepository,
    private val sessionDebitPort: SessionDebitPort,
) : ExecuteWorkflowUseCase {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun execute(cmd: ExecuteWorkflowCommand): Result<WorkflowPlanId> {
        val priorSession = sessionDebitPort.sumDebitsForSession(cmd.tenantId, cmd.agentContext.sessionId)
        val priorHour = sessionDebitPort.sumDebitsLastHour(cmd.tenantId, cmd.agentContext.apiKeyPrefix)
        val ledgerIntent =
            LedgerIntent(
                lines =
                    cmd.steps.flatMap { step ->
                        step.lines.map { line ->
                            LedgerIntentLine(
                                accountId = line.accountId,
                                entryType = line.entryType,
                                monetaryEntry = line.monetaryEntry,
                            )
                        }
                    },
                priorSessionDebitTotal = priorSession,
                priorHourlyDebitTotal = priorHour,
            )

        val now = Instant.now()
        val planId = WorkflowPlanId.generate()

        val rules = policyRepository.findEffective(cmd.tenantId, cmd.agentContext.apiKeyPrefix)
        // Default deny-all: if a tenant has no configured rules every agent debit is blocked
        // until an admin explicitly sets a permissive rule via POST /api/v1/admin/policy-rules.
        val effectiveRules = rules.ifEmpty { listOf(PolicyRule.MaxDebitPerSession(MonetaryAmount.ZERO)) }
        val policyResult = PolicyGuard.evaluate(cmd.agentContext, ledgerIntent, effectiveRules)
        if (policyResult is PolicyEvaluationResult.Denied) {
            // Record the denied attempt durably (separate transaction) — throwing rolls back
            // the business transaction, so an in-transaction write would leave no audit trail
            // of the blocked agent action.
            val violationSummary = policyResult.violations.joinToString("; ") { it.message }
            recordDurableOrThrow(
                AgentAuditEvent.failed(
                    workflowPlanId = planId,
                    tenantId = cmd.tenantId,
                    agentContext = cmd.agentContext,
                    outcome = "Policy denied: $violationSummary",
                ),
            )
            throw PolicyViolationException(policyResult.violations)
        }

        var plan =
            WorkflowPlan.create(
                id = planId,
                tenantId = cmd.tenantId,
                agentContext = cmd.agentContext,
                stepDescriptions = cmd.steps.map { it.description.ifBlank { it.idempotencyKey } },
                createdAt = now,
            )
        workflowPlanRepository.insert(plan)

        // Durable "attempt started" record — survives a later rollback of the business tx.
        // Must fail closed: if this can't be persisted, execution must not proceed un-audited.
        recordDurableOrThrow(
            AgentAuditEvent.pending(
                workflowPlanId = planId,
                tenantId = cmd.tenantId,
                agentContext = cmd.agentContext,
                intent = cmd.agentContext.intent,
            ),
        )

        workflowPlanRepository.updateStatus(planId, cmd.tenantId, WorkflowStatus.EXECUTING)

        cmd.steps.forEachIndexed { index, step ->
            val txCmd =
                PostTransactionCommand(
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
                    // Durable failure record — the throw below rolls back the business tx
                    // (plan + committed steps), so this must commit in its own transaction.
                    recordDurableSwallowing(
                        AgentAuditEvent.failed(
                            workflowPlanId = planId,
                            tenantId = cmd.tenantId,
                            agentContext = cmd.agentContext,
                            outcome = "Step $index failed: ${ex.message}",
                        ),
                    )
                    throw RuntimeException("Workflow step $index failed: ${ex.message}", ex)
                },
            )
        }

        val completedAt = Instant.now()
        val committedPlan =
            plan
                .withStatus(WorkflowStatus.COMMITTED)
                .copy(completedAt = completedAt)
        workflowPlanRepository.updateStatus(planId, cmd.tenantId, WorkflowStatus.COMMITTED, completedAt = completedAt)

        // Durable completion record, own transaction — a failure here must not roll back
        // a workflow status transition that already reflects an already-executed side effect.
        recordDurableSwallowing(
            AgentAuditEvent.completed(
                workflowPlanId = planId,
                tenantId = cmd.tenantId,
                agentContext = cmd.agentContext,
                outcome = "Workflow committed with ${cmd.steps.size} step(s)",
            ),
        )

        webhookOutboxRepository.save(WebhookOutboxEntry.workflowCommitted(committedPlan))

        return Result.success(planId)
    }

    /**
     * Writes an audit event in its own committed transaction via [AgentAuditRecorder].
     * Propagates on failure — used strictly pre-execution, where nothing has happened yet
     * that would need to survive an aborted request. Never write audit records for actions
     * that already executed via this variant: an audit-write hiccup must not roll back a
     * state transition that already reflects reality.
     */
    private fun recordDurableOrThrow(event: AgentAuditEvent) {
        agentAuditRecorder.recordDurable(event)
    }

    /**
     * Writes an audit event in its own committed transaction via [AgentAuditRecorder].
     * A failure to persist the audit event is logged but never masks the original outcome
     * (failure/success) that the caller is about to propagate — used once execution has
     * already happened (or is already known to have failed), so aborting further would not
     * undo anything.
     */
    private fun recordDurableSwallowing(event: AgentAuditEvent) {
        runCatching { agentAuditRecorder.recordDurable(event) }
            .onFailure { log.error("Failed to write durable agent audit event for plan {}", event.workflowPlanId.value, it) }
    }
}
