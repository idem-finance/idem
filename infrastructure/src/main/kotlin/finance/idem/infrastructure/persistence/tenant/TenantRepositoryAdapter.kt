package finance.idem.infrastructure.persistence.tenant

import finance.idem.application.port.TenantRepository
import finance.idem.application.tenant.TenantWebhookConfig
import finance.idem.core.TenantId
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class TenantRepositoryAdapter(
    private val jpaRepository: TenantJpaRepository,
) : TenantRepository {
    /**
     * Cross-tenant — deliberately does NOT set `app.tenant_id`. Relies on
     * `tenants` having NO FORCE RLS (V13): the table-owner role can resolve
     * any tenant's webhook config while WebhookOutboxPoller iterates
     * cross-tenant dispatchable rows. That same NO FORCE exemption also covers
     * every other column on this row this class touches below (`hmac_key`,
     * `billing_customer_id`, `plan`, rate limits, `feature_flags`) — see V29.
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
            )
        jpaRepository.save(updated)
    }
}
