package finance.idem.infrastructure.persistence.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import finance.idem.application.outbox.OutboxStatus
import finance.idem.application.outbox.WebhookOutboxDispatch
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
                status = OutboxStatus.PENDING,
                attempts = 0,
                nextRetryAt = Instant.now(),
                lastError = null,
                createdAt = Instant.now(),
                deliveredAt = null,
            )
        )
    }

    @Transactional(readOnly = true)
    fun findPendingOrFailed(tenantId: TenantId): List<WebhookOutboxDataModel> {
        setTenantId(tenantId)
        return jpaRepository.findByTenantIdAndStatusInOrderByCreatedAtAsc(
            tenantId.value,
            listOf(OutboxStatus.PENDING, OutboxStatus.FAILED),
        )
    }

    @Transactional
    override fun markDelivered(id: UUID, tenantId: TenantId) {
        setTenantId(tenantId)
        jpaRepository.markDelivered(id.toString(), tenantId.value.toString(), Instant.now())
    }

    @Transactional
    override fun markFailedForRetry(id: UUID, tenantId: TenantId, attempts: Int, nextRetryAt: Instant, lastError: String?) {
        setTenantId(tenantId)
        jpaRepository.markFailedForRetry(id.toString(), tenantId.value.toString(), attempts, nextRetryAt, lastError)
    }

    @Transactional
    override fun markDead(id: UUID, tenantId: TenantId, lastError: String?) {
        setTenantId(tenantId)
        jpaRepository.markDead(id.toString(), tenantId.value.toString(), lastError)
    }

    /**
     * Cross-tenant — deliberately does NOT call `setTenantId`. Relies on
     * `webhook_outbox` having NO FORCE RLS (V12): the table-owner role sees
     * PENDING/FAILED rows across all tenants with no `app.tenant_id` set.
     */
    @Transactional(readOnly = true)
    override fun findDispatchable(limit: Int): List<WebhookOutboxDispatch> =
        jpaRepository.findDispatchable(limit).map {
            WebhookOutboxDispatch(
                id = it.id,
                tenantId = TenantId(it.tenantId),
                eventType = it.eventType,
                payload = it.payload,
                attempts = it.attempts,
            )
        }
}
