package finance.idem.application.agentic

import finance.idem.core.TenantId
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.AgentContext

data class RollbackWorkflowCommand(
    val tenantId: TenantId,
    val agentContext: AgentContext,
    val workflowPlanId: WorkflowPlanId,
    val reason: String,
    val createdBy: String,
)
