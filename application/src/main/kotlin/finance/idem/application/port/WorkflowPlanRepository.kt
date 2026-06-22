package finance.idem.application.port

import finance.idem.core.TenantId
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.WorkflowPlan
import finance.idem.core.agentic.WorkflowPlanStatus
import finance.idem.core.agentic.WorkflowPlanStep
import java.time.Instant

interface WorkflowPlanRepository {
    fun insert(plan: WorkflowPlan)
    fun updateStatus(id: WorkflowPlanId, tenantId: TenantId, status: WorkflowPlanStatus, committedAt: Instant?)
    fun updateStep(id: WorkflowPlanId, tenantId: TenantId, step: WorkflowPlanStep)
    fun findById(id: WorkflowPlanId, tenantId: TenantId): WorkflowPlan?
}
