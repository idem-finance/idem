package finance.idem.core.agentic

import finance.idem.core.TenantId
import finance.idem.core.WorkflowPlanId
import java.time.Instant

interface WorkflowPlanRepository {
    fun insert(plan: WorkflowPlan)

    fun updateStatus(
        id: WorkflowPlanId,
        tenantId: TenantId,
        status: WorkflowStatus,
        completedAt: Instant? = null,
        rolledBackAt: Instant? = null,
        rollbackReason: String? = null,
    )

    fun updateStep(
        id: WorkflowPlanId,
        tenantId: TenantId,
        step: WorkflowStep,
    )

    fun findById(
        id: WorkflowPlanId,
        tenantId: TenantId,
    ): WorkflowPlan?
}
