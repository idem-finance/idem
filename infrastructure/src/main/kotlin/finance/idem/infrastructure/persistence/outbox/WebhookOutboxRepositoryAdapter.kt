package finance.idem.infrastructure.persistence.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.core.TenantId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Component
class WebhookOutboxRepositoryAdapter(
    val jpaRepository: WebhookOutboxJpaRepository,
    private val entityManager: EntityManager,
    private val objectMapper: ObjectMapper,
) : WebhookOutboxRepository {

    private fun setTenantId(tenantId: TenantId) {
        // UUID contains only hex digits and dashes — safe to interpolate without binding
        entityManager.createNativeQuery("SET LOCAL app.tenant_id = '${tenantId.value}'")
            .executeUpdate()
    }

    @Transactional
    override fun save(entry: WebhookOutboxEntry) {
        setTenantId(entry.tenantId)
        val payload = objectMapper.writeValueAsString(mapOf(
            "transactionId" to entry.transactionId.value.toString(),
            "eventType" to entry.eventType,
            "occurredAt" to entry.occurredAt.toString(),
        ))
        jpaRepository.save(
            WebhookOutboxDataModel(
                id = entry.id,
                tenantId = entry.tenantId.value,
                transactionId = entry.transactionId.value,
                eventType = entry.eventType,
                payload = payload,
                dispatched = false,
                retryCount = 0,
                lastError = null,
                createdAt = Instant.now(),
                dispatchedAt = null,
            )
        )
    }

    @Transactional(readOnly = true)
    fun findPending(tenantId: TenantId): List<WebhookOutboxDataModel> {
        setTenantId(tenantId)
        return jpaRepository.findByTenantIdAndDispatchedFalseOrderByCreatedAtAsc(tenantId.value)
    }

    @Transactional
    fun markDispatched(id: UUID, tenantId: TenantId) {
        setTenantId(tenantId)
        jpaRepository.markDispatched(id.toString(), tenantId.value.toString(), Instant.now())
    }
}
