package finance.idem.infrastructure.persistence.audit

import com.fasterxml.jackson.databind.ObjectMapper
import finance.idem.application.port.AgentAuditRepository
import finance.idem.application.port.AgentAuditView
import finance.idem.core.TenantId
import finance.idem.core.agentic.AgentAuditEvent
import finance.idem.core.tenant.TenantConfigRepository
import finance.idem.infrastructure.persistence.setRlsTenantId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class AgentAuditRepositoryAdapter(
    private val jpaRepository: AgentAuditEventJpaRepository,
    private val entityManager: EntityManager,
    private val objectMapper: ObjectMapper,
    private val auditProperties: AuditProperties,
    private val tenantConfigRepository: TenantConfigRepository,
) : AgentAuditRepository {
    @Transactional
    override fun save(event: AgentAuditEvent) {
        entityManager.setRlsTenantId(event.tenantId)
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "tenantId" to event.tenantId.value.toString(),
                    "workflowPlanId" to event.workflowPlanId.value.toString(),
                    "agentId" to event.agentContext.agentId,
                    "sessionId" to event.agentContext.sessionId,
                    "intent" to event.intent,
                    "status" to event.status.name,
                    "outcome" to event.outcome,
                ),
            )
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
                hmac = event.computeHmac(resolveHmacKey(event.tenantId)),
                occurredAt = event.occurredAt,
            ),
        )
    }

    /**
     * Per-tenant HMAC key, falling back to the global secret when a tenant has none
     * configured — required so installs upgrading from a single global secret keep
     * verifying previously-signed events without a backfill.
     */
    private fun resolveHmacKey(tenantId: TenantId): String =
        tenantConfigRepository.findByTenantId(tenantId)?.hmacKey ?: auditProperties.hmacSecret

    @Transactional
    @Suppress("UNCHECKED_CAST")
    override fun findByFilter(
        tenantId: TenantId,
        sessionId: String?,
        from: Instant?,
        to: Instant?,
        limit: Int,
    ): List<AgentAuditView> {
        entityManager.setRlsTenantId(tenantId)
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
        val eventType =
            when (status) {
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
}
