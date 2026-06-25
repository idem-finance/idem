package finance.idem.mcp

data class AuditLogResult(
    val auditEvents: List<AuditEventItem>,
    val total: Int,
)

data class AuditEventItem(
    val id: String,
    val workflowPlanId: String,
    val agentId: String,
    val model: String?,
    val sessionId: String,
    val eventType: String,
    val intentPayload: String?,
    val status: String,
    val occurredAt: String,
    val completedAt: String?,
    val hmacSignature: String,
)
