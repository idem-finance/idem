package finance.idem.application.port

import finance.idem.core.TenantId
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.WorkflowPlan

interface WorkflowPlanRepository {
    fun save(plan: WorkflowPlan)
    fun findById(id: WorkflowPlanId, tenantId: TenantId): WorkflowPlan?
}
