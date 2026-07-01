package finance.idem.core.agentic

import finance.idem.core.TenantId
import finance.idem.core.WorkflowPlanId
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class AgentAuditStatus { PENDING, COMPLETED, FAILED }

data class AgentAuditEvent(
    val id: UUID,
    val workflowPlanId: WorkflowPlanId,
    val tenantId: TenantId,
    val agentContext: AgentContext,
    val status: AgentAuditStatus,
    val intent: String?,
    val outcome: String?,
    val occurredAt: Instant,
) {
    companion object {
        fun pending(
            workflowPlanId: WorkflowPlanId,
            tenantId: TenantId,
            agentContext: AgentContext,
            intent: String?,
        ): AgentAuditEvent =
            AgentAuditEvent(
                id = UUID.randomUUID(),
                workflowPlanId = workflowPlanId,
                tenantId = tenantId,
                agentContext = agentContext,
                status = AgentAuditStatus.PENDING,
                intent = intent,
                outcome = null,
                occurredAt = Instant.now(),
            )

        fun completed(
            workflowPlanId: WorkflowPlanId,
            tenantId: TenantId,
            agentContext: AgentContext,
            outcome: String,
        ): AgentAuditEvent =
            AgentAuditEvent(
                id = UUID.randomUUID(),
                workflowPlanId = workflowPlanId,
                tenantId = tenantId,
                agentContext = agentContext,
                status = AgentAuditStatus.COMPLETED,
                intent = agentContext.intent,
                outcome = outcome,
                occurredAt = Instant.now(),
            )

        fun failed(
            workflowPlanId: WorkflowPlanId,
            tenantId: TenantId,
            agentContext: AgentContext,
            outcome: String,
        ): AgentAuditEvent =
            AgentAuditEvent(
                id = UUID.randomUUID(),
                workflowPlanId = workflowPlanId,
                tenantId = tenantId,
                agentContext = agentContext,
                status = AgentAuditStatus.FAILED,
                intent = agentContext.intent,
                outcome = outcome,
                occurredAt = Instant.now(),
            )
    }

    fun computeHmac(secret: String): String {
        // Escape \ then | in free-text fields so neither can shift field boundaries.
        // \N is the null sentinel — distinct from the empty string and from the literal "null".
        fun String?.safe() = this?.replace("\\", "\\\\")?.replace("|", "\\|") ?: "\\N"
        val canonical =
            "$id|${workflowPlanId.value}|${tenantId.value}|" +
                "${agentContext.agentId}|${agentContext.sessionId}|${intent.safe()}|" +
                "${status.name}|${outcome.safe()}|${occurredAt.toEpochMilli()}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)))
    }
}
