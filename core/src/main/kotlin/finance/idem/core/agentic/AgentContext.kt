package finance.idem.core.agentic

import finance.idem.core.WorkflowPlanId

// agentId and sessionId are caller-supplied strings persisted verbatim in the audit log.
// They are not verified against a registry — trust derives from the API key, not these fields.
// apiKeyPrefix is the verified first-12-char prefix of the API key extracted from the
// authenticated principal; it ties the audit entry to an actual credential independent of
// what the caller claims as agentId.
// TODO: verify agentId against a registered agent table when agent identity management is added.
data class AgentContext(
    val agentId: String,
    val sessionId: String,
    val workflowPlanId: WorkflowPlanId? = null,
    val intent: String? = null,
    val apiKeyPrefix: String? = null,
)
