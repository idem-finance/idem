package finance.idem.infrastructure.persistence.audit

import com.fasterxml.jackson.databind.ObjectMapper
import finance.idem.application.port.AgentAuditRepository
import finance.idem.core.TenantId
import finance.idem.core.agentic.AgentAuditEvent
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
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

    private fun hmacSha256(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
    }
}
