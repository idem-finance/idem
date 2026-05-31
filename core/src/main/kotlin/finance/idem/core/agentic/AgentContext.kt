package finance.idem.core.agentic

import finance.idem.core.WorkflowPlanId

data class AgentContext(
    val agentId: String,
    val sessionId: String,
    val workflowPlanId: WorkflowPlanId? = null,
    val intent: String? = null,
)
