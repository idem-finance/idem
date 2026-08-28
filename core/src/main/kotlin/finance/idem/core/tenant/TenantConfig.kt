package finance.idem.core.tenant

import finance.idem.core.TenantId
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
) {
    val isSuspended: Boolean get() = suspendedAt != null

    fun hasFeature(flag: String): Boolean = featureFlags.contains(flag)

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
            )
    }
}
