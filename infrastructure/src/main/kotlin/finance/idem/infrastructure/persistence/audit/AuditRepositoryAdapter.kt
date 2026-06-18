package finance.idem.infrastructure.persistence.audit

import com.fasterxml.jackson.databind.ObjectMapper
import finance.idem.application.audit.AuditEntry
import finance.idem.application.port.AuditRepository
import finance.idem.core.TenantId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class AuditRepositoryAdapter(
    private val jpaRepository: AuditLogJpaRepository,
    private val entityManager: EntityManager,
    private val objectMapper: ObjectMapper,
    private val auditProperties: AuditProperties,
) : AuditRepository {

    private fun setTenantId(tenantId: TenantId) {
        entityManager.createNativeQuery("SET LOCAL app.tenant_id = '${tenantId.value}'")
            .executeUpdate()
    }

    @Transactional
    override fun save(entry: AuditEntry) {
        setTenantId(entry.tenantId)
        val payload = objectMapper.writeValueAsString(mapOf(
            "transactionId" to entry.transactionId.value.toString(),
            "action" to entry.action,
            "agentId" to entry.agentContext?.agentId,
            "sessionId" to entry.agentContext?.sessionId,
            "workflowPlanId" to entry.agentContext?.workflowPlanId?.value?.toString(),
            "intent" to entry.agentContext?.intent,
            "createdBy" to entry.createdBy,
        ))
        jpaRepository.save(
            AuditLogDataModel(
                id = entry.id,
                tenantId = entry.tenantId.value,
                transactionId = entry.transactionId.value,
                agentId = entry.agentContext?.agentId,
                intent = entry.agentContext?.intent,
                action = entry.action,
                createdBy = entry.createdBy,
                payload = payload,
                hmac = hmacSha256(payload, auditProperties.hmacSecret),
                occurredAt = entry.occurredAt,
            )
        )
    }

    private fun hmacSha256(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
    }
}
