package finance.idem.core.agentic

import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.WorkflowPlanId
import java.time.Instant

enum class WorkflowPlanStatus { PLANNED, EXECUTING, COMMITTED, ROLLED_BACK }
enum class WorkflowStepStatus { PENDING, EXECUTED, FAILED }

data class WorkflowPlanStep(
    val stepIndex: Int,
    val idempotencyKey: String,
    val status: WorkflowStepStatus,
    val transactionId: TransactionId?,
)

data class WorkflowPlan internal constructor(
    val id: WorkflowPlanId,
    val tenantId: TenantId,
    val agentContext: AgentContext,
    val status: WorkflowPlanStatus,
    val steps: List<WorkflowPlanStep>,
    val occurredAt: Instant,
    val committedAt: Instant?,
) {
    companion object {
        fun create(
            id: WorkflowPlanId,
            tenantId: TenantId,
            agentContext: AgentContext,
            stepIdempotencyKeys: List<String>,
            occurredAt: Instant,
        ): WorkflowPlan = WorkflowPlan(
            id = id,
            tenantId = tenantId,
            agentContext = agentContext,
            status = WorkflowPlanStatus.PLANNED,
            steps = stepIdempotencyKeys.mapIndexed { index, key ->
                WorkflowPlanStep(stepIndex = index, idempotencyKey = key, status = WorkflowStepStatus.PENDING, transactionId = null)
            },
            occurredAt = occurredAt,
            committedAt = null,
        )

        fun reconstitute(
            id: WorkflowPlanId,
            tenantId: TenantId,
            agentContext: AgentContext,
            status: WorkflowPlanStatus,
            steps: List<WorkflowPlanStep>,
            occurredAt: Instant,
            committedAt: Instant?,
        ): WorkflowPlan = WorkflowPlan(
            id = id,
            tenantId = tenantId,
            agentContext = agentContext,
            status = status,
            steps = steps,
            occurredAt = occurredAt,
            committedAt = committedAt,
        )
    }

    fun withStatus(newStatus: WorkflowPlanStatus): WorkflowPlan = copy(status = newStatus)

    fun withStepExecuted(stepIndex: Int, txId: TransactionId): WorkflowPlan = copy(
        steps = steps.map { step ->
            if (step.stepIndex == stepIndex) step.copy(status = WorkflowStepStatus.EXECUTED, transactionId = txId)
            else step
        }
    )

    fun withStepFailed(stepIndex: Int): WorkflowPlan = copy(
        steps = steps.map { step ->
            if (step.stepIndex == stepIndex) step.copy(status = WorkflowStepStatus.FAILED)
            else step
        }
    )

    fun executedSteps(): List<WorkflowPlanStep> = steps.filter { it.status == WorkflowStepStatus.EXECUTED }
}
