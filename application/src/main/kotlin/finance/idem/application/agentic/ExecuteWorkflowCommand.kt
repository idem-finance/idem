package finance.idem.application.agentic

import finance.idem.core.TenantId
import finance.idem.core.agentic.AgentContext

data class ExecuteWorkflowCommand(
    val tenantId: TenantId,
    val agentContext: AgentContext,
    val steps: List<WorkflowStepCommand>,
    val createdBy: String,
)
