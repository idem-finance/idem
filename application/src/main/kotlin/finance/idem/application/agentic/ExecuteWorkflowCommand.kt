package finance.idem.application.agentic

import finance.idem.core.TenantId
import finance.idem.core.agentic.AgentContext
import finance.idem.core.agentic.PolicyRule

data class ExecuteWorkflowCommand(
    val tenantId: TenantId,
    val agentContext: AgentContext,
    val steps: List<WorkflowStepCommand>,
    val policyRules: List<PolicyRule> = emptyList(),
    val createdBy: String,
)
