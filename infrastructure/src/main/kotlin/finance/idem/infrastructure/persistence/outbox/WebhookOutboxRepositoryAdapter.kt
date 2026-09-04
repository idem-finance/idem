package finance.idem.infrastructure.persistence.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import finance.idem.application.outbox.OutboxStatus
import finance.idem.application.outbox.WebhookOutboxDispatch
import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.core.TenantId
import finance.idem.infrastructure.persistence.setRlsTenantId
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
    @Transactional
    override fun save(entry: WebhookOutboxEntry) {
        entityManager.setRlsTenantId(entry.tenantId)
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "transactionId" to entry.transactionId.value.toString(),
                    "eventType" to entry.eventType,
                    "occurredAt" to entry.occurredAt.toString(),
                ),
            )
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
            ),
        )
    }

    @Transactional(readOnly = true)
    fun findPendingOrFailed(tenantId: TenantId): List<WebhookOutboxDataModel> {
        entityManager.setRlsTenantId(tenantId)
        return jpaRepository.findByTenantIdAndStatusInOrderByCreatedAtAsc(
            tenantId.value,
            listOf(OutboxStatus.PENDING, OutboxStatus.FAILED),
        )
    }

    @Transactional
    override fun markDelivered(
        id: UUID,
        tenantId: TenantId,
    ) {
        entityManager.setRlsTenantId(tenantId)
        jpaRepository.markDelivered(id.toString(), tenantId.value.toString(), Instant.now())
    }

    @Transactional
    override fun markFailedForRetry(
        id: UUID,
        tenantId: TenantId,
        attempts: Int,
        nextRetryAt: Instant,
        lastError: String?,
    ) {
        entityManager.setRlsTenantId(tenantId)
        jpaRepository.markFailedForRetry(id.toString(), tenantId.value.toString(), attempts, nextRetryAt, lastError)
    }

    @Transactional
    override fun markDead(
        id: UUID,
        tenantId: TenantId,
        lastError: String?,
    ) {
        entityManager.setRlsTenantId(tenantId)
        jpaRepository.markDead(id.toString(), tenantId.value.toString(), lastError)
    }

    /**
     * Cross-tenant — deliberately does NOT call `setTenantId`. `webhook_outbox` carries a
     * SELECT-only, idem_app-scoped `service_cross_tenant_read` policy (V31) specifically
     * for this read, so PENDING/FAILED rows across all tenants are visible with no
     * `app.tenant_id` set. Every write above sets it as usual — that policy is SELECT only.
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
