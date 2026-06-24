package finance.idem.application.port

import java.time.Instant
import java.util.UUID

data class AgentAuditView(
    val id: UUID,
    val workflowPlanId: UUID,
    val agentId: String,
    val sessionId: String,
    val eventType: String,
    val intentPayload: String?,
    val status: String,
    val occurredAt: Instant,
    val completedAt: Instant?,
    val hmacSignature: String,
)
