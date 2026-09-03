package finance.idem.infrastructure.persistence.tenant

import finance.idem.application.port.TenantRepository
import finance.idem.application.tenant.TenantWebhookConfig
import finance.idem.core.TenantId
import finance.idem.core.tenant.Tenant
import finance.idem.infrastructure.persistence.setRlsTenantId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class TenantRepositoryAdapter(
    private val jpaRepository: TenantJpaRepository,
    private val entityManager: EntityManager,
) : TenantRepository {
    /**
     * Cross-tenant — deliberately does NOT set `app.tenant_id`. `tenants` carries a
     * SELECT-only, idem_app-scoped `service_cross_tenant_read` policy (V31) specifically
     * for this read, so WebhookOutboxPoller can resolve any tenant's webhook config while
     * iterating cross-tenant dispatchable rows. That same policy covers every other column
     * on this row this class touches below (`hmac_key`, `billing_customer_id`, `plan`,
     * rate limits, `feature_flags`, monthly usage limits, `organization_name`,
     * `contact_email`) — see V28/V29/V30. Writes below are NOT covered by it (SELECT
     * only) and set `app.tenant_id` like every other tenant-scoped write in this codebase.
     */
    @Transactional(readOnly = true)
    override fun findWebhookConfig(tenantId: TenantId): TenantWebhookConfig? {
        val tenant = jpaRepository.findById(tenantId.value).orElse(null) ?: return null
        val url = tenant.webhookUrl
        val secret = tenant.webhookSecret
        return if (!url.isNullOrBlank() && !secret.isNullOrBlank()) {
            TenantWebhookConfig(webhookUrl = url, webhookSecret = secret)
        } else {
            null
        }
    }

    @Transactional
    override fun upsertWebhookConfig(
        tenantId: TenantId,
        config: TenantWebhookConfig,
    ) {
        entityManager.setRlsTenantId(tenantId)
        val now = Instant.now()
        val existing = jpaRepository.findById(tenantId.value).orElse(null)
        val updated =
            TenantDataModel(
                id = tenantId.value,
                webhookUrl = config.webhookUrl,
                webhookSecret = config.webhookSecret,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                plan = existing?.plan ?: "OPEN_SOURCE",
                rateLimitPerSecond = existing?.rateLimitPerSecond,
                rateLimitPerMinute = existing?.rateLimitPerMinute,
                featureFlags = existing?.featureFlags ?: "",
                hmacKey = existing?.hmacKey,
                billingCustomerId = existing?.billingCustomerId,
                suspendedAt = existing?.suspendedAt,
                monthlyTransactionLimit = existing?.monthlyTransactionLimit,
                monthlyApiCallLimit = existing?.monthlyApiCallLimit,
                monthlyChainEventLimit = existing?.monthlyChainEventLimit,
                monthlyWebhookDeliveryLimit = existing?.monthlyWebhookDeliveryLimit,
                monthlyEntryLimit = existing?.monthlyEntryLimit,
                organizationName = existing?.organizationName,
                contactEmail = existing?.contactEmail,
            )
        jpaRepository.save(updated)
    }

    @Transactional
    override fun create(tenant: Tenant) {
        entityManager.setRlsTenantId(tenant.id)
        val created =
            TenantDataModel(
                id = tenant.id.value,
                webhookUrl = null,
                webhookSecret = null,
                createdAt = tenant.createdAt,
                updatedAt = tenant.createdAt,
                organizationName = tenant.organizationName,
                contactEmail = tenant.contactEmail,
            )
        jpaRepository.save(created)
    }
}
