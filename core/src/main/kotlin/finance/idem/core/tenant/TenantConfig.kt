package finance.idem.core.tenant

import finance.idem.core.TenantId
import finance.idem.core.usage.MetricType
import java.time.Instant

data class TenantConfig(
    val tenantId: TenantId,
    val plan: TenantPlan,
    val rateLimitPerSecond: Int?,
    val rateLimitPerMinute: Int?,
    val featureFlags: Set<String>,
    val hmacKey: String?,
    val billingCustomerId: String?,
    val createdAt: Instant,
    val suspendedAt: Instant?,
    val monthlyTransactionLimit: Long? = null,
    val monthlyApiCallLimit: Long? = null,
    val monthlyChainEventLimit: Long? = null,
    val monthlyWebhookDeliveryLimit: Long? = null,
    val monthlyEntryLimit: Long? = null,
) {
    val isSuspended: Boolean get() = suspendedAt != null

    fun hasFeature(flag: String): Boolean = featureFlags.contains(flag)

    /** The configured monthly limit for this metric, or `null` if unlimited/not configured. */
    fun limitFor(metricType: MetricType): Long? =
        when (metricType) {
            MetricType.TRANSACTION_COUNT -> monthlyTransactionLimit
            MetricType.API_CALL_COUNT -> monthlyApiCallLimit
            MetricType.CHAIN_EVENT_COUNT -> monthlyChainEventLimit
            MetricType.WEBHOOK_DELIVERY_COUNT -> monthlyWebhookDeliveryLimit
            MetricType.ENTRY_COUNT -> monthlyEntryLimit
        }

    companion object {
        /**
         * Config for a tenant with no persisted row — the common case for self-hosted
         * installs that never connect a billing system. Plan defaults to OPEN_SOURCE and
         * every limit/flag/key is treated as "not configured", never as an error.
         */
        fun default(tenantId: TenantId): TenantConfig =
            TenantConfig(
                tenantId = tenantId,
                plan = TenantPlan.OPEN_SOURCE,
                rateLimitPerSecond = null,
                rateLimitPerMinute = null,
                featureFlags = emptySet(),
                hmacKey = null,
                billingCustomerId = null,
                createdAt = Instant.now(),
                suspendedAt = null,
                monthlyTransactionLimit = null,
                monthlyApiCallLimit = null,
                monthlyChainEventLimit = null,
                monthlyWebhookDeliveryLimit = null,
                monthlyEntryLimit = null,
            )
    }
}
