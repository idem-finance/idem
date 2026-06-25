package finance.idem.infrastructure.persistence.audit

import com.fasterxml.jackson.databind.ObjectMapper
import finance.idem.application.port.AgentAuditRepository
import finance.idem.application.port.AgentAuditView
import finance.idem.core.TenantId
import finance.idem.core.agentic.AgentAuditEvent
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class AgentAuditRepositoryAdapter(
    private val jpaRepository: AgentAuditEventJpaRepository,
    private val entityManager: EntityManager,
    private val objectMapper: ObjectMapper,
    private val auditProperties: AuditProperties,
) : AgentAuditRepository {

    private fun setTenantId(tenantId: TenantId) {
        entityManager.createNativeQuery("SET LOCAL app.tenant_id = '${tenantId.value}'")
            .executeUpdate()
    }

    @Transactional
    override fun save(event: AgentAuditEvent) {
        setTenantId(event.tenantId)
        val payload = objectMapper.writeValueAsString(mapOf(
            "tenantId" to event.tenantId.value.toString(),
            "workflowPlanId" to event.workflowPlanId.value.toString(),
            "agentId" to event.agentContext.agentId,
            "sessionId" to event.agentContext.sessionId,
            "intent" to event.intent,
            "status" to event.status.name,
            "outcome" to event.outcome,
        ))
        jpaRepository.save(
            AgentAuditEventDataModel(
                id = event.id,
                workflowPlanId = event.workflowPlanId.value,
                tenantId = event.tenantId.value,
                agentId = event.agentContext.agentId,
                sessionId = event.agentContext.sessionId,
                intent = event.intent,
                status = event.status.name,
                outcome = event.outcome,
                payload = payload,
                hmac = hmacSha256(payload, auditProperties.hmacSecret),
                occurredAt = event.occurredAt,
            )
        )
    }

    @Transactional
    @Suppress("UNCHECKED_CAST")
    override fun findByFilter(
        tenantId: TenantId,
        sessionId: String?,
        from: Instant?,
        to: Instant?,
        limit: Int,
    ): List<AgentAuditView> {
        setTenantId(tenantId)
        val effectiveLimit = limit.coerceIn(1, 200)
        // Build query dynamically to avoid PostgreSQL NULL type inference errors
        // that occur when passing typed null parameters in native SQL prepared statements.
        val sql = StringBuilder("SELECT * FROM agent_audit_events WHERE tenant_id = :tenantId")
        if (sessionId != null) sql.append(" AND session_id = :sessionId")
        if (from != null) sql.append(" AND occurred_at >= :from")
        if (to != null) sql.append(" AND occurred_at <= :to")
        sql.append(" ORDER BY occurred_at DESC")

        val query = entityManager.createNativeQuery(sql.toString(), AgentAuditEventDataModel::class.java)
        query.setParameter("tenantId", tenantId.value)
        if (sessionId != null) query.setParameter("sessionId", sessionId)
        if (from != null) query.setParameter("from", from)
        if (to != null) query.setParameter("to", to)
        query.maxResults = effectiveLimit

        return (query.resultList as List<AgentAuditEventDataModel>).map { it.toView() }
    }

    private fun AgentAuditEventDataModel.toView(): AgentAuditView {
        val eventType = when (status) {
            "PENDING" -> "AGENT_ACTION_STARTED"
            "COMPLETED" -> "AGENT_ACTION_COMPLETED"
            "FAILED" -> "AGENT_ACTION_FAILED"
            else -> status
        }
        val completedAt = if (status == "COMPLETED" || status == "FAILED") occurredAt else null
        return AgentAuditView(
            id = id,
            workflowPlanId = workflowPlanId,
            agentId = agentId,
            sessionId = sessionId,
            eventType = eventType,
            intentPayload = intent,
            status = status,
            occurredAt = occurredAt,
            completedAt = completedAt,
            hmacSignature = hmac,
        )
    }

    private fun hmacSha256(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
    }
}
