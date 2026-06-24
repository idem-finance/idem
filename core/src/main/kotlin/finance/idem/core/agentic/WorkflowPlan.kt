package finance.idem.core.agentic

import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.WorkflowPlanId
import java.time.Instant
import java.util.UUID

enum class WorkflowStatus { PLANNED, EXECUTING, COMMITTED, ROLLED_BACK, FAILED }

data class WorkflowPlan internal constructor(
    val id: WorkflowPlanId,
    val tenantId: TenantId,
    val agentContext: AgentContext,
    val status: WorkflowStatus,
    val steps: List<WorkflowStep>,
    val createdAt: Instant,
    val completedAt: Instant?,
    val rolledBackAt: Instant?,
    val rollbackReason: String?,
) {
    companion object {
        fun create(
            id: WorkflowPlanId,
            tenantId: TenantId,
            agentContext: AgentContext,
            stepDescriptions: List<String>,
            createdAt: Instant,
        ): WorkflowPlan = WorkflowPlan(
            id = id,
            tenantId = tenantId,
            agentContext = agentContext,
            status = WorkflowStatus.PLANNED,
            steps = stepDescriptions.mapIndexed { index, description ->
                WorkflowStep(
                    stepId = UUID.randomUUID(),
                    stepOrder = index,
                    description = description,
                    transactionId = null,
                    status = StepStatus.PENDING,
                    executedAt = null,
                    compensatingTransactionId = null,
                )
            },
            createdAt = createdAt,
            completedAt = null,
            rolledBackAt = null,
            rollbackReason = null,
        )

        fun reconstitute(
            id: WorkflowPlanId,
            tenantId: TenantId,
            agentContext: AgentContext,
            status: WorkflowStatus,
            steps: List<WorkflowStep>,
            createdAt: Instant,
            completedAt: Instant?,
            rolledBackAt: Instant?,
            rollbackReason: String?,
        ): WorkflowPlan = WorkflowPlan(
            id = id,
            tenantId = tenantId,
            agentContext = agentContext,
            status = status,
            steps = steps,
            createdAt = createdAt,
            completedAt = completedAt,
            rolledBackAt = rolledBackAt,
            rollbackReason = rollbackReason,
        )
    }

    fun withStatus(newStatus: WorkflowStatus): WorkflowPlan = copy(status = newStatus)

    fun withStepExecuted(stepOrder: Int, txId: TransactionId): WorkflowPlan = copy(
        steps = steps.map { step ->
            if (step.stepOrder == stepOrder)
                step.copy(status = StepStatus.EXECUTED, transactionId = txId, executedAt = Instant.now())
            else step
        }
    )

    fun withStepFailed(stepOrder: Int): WorkflowPlan = copy(
        steps = steps.map { step ->
            if (step.stepOrder == stepOrder) step.copy(status = StepStatus.FAILED)
            else step
        }
    )

    fun withStepRolledBack(stepOrder: Int, compensatingTxId: TransactionId): WorkflowPlan = copy(
        steps = steps.map { step ->
            if (step.stepOrder == stepOrder)
                step.copy(status = StepStatus.ROLLED_BACK, compensatingTransactionId = compensatingTxId)
            else step
        }
    )

    fun executedSteps(): List<WorkflowStep> = steps.filter { it.status == StepStatus.EXECUTED }
}
