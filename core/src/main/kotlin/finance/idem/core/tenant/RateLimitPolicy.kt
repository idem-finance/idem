package finance.idem.core.tenant

/**
 * The effective per-tenant request limits after applying [RateLimitPolicy.resolve] —
 * `null` in either field means that dimension is unlimited.
 */
data class RateLimitDecision(
    val perSecond: Int?,
    val perMinute: Int?,
) {
    val isUnlimited: Boolean get() = perSecond == null && perMinute == null
}

/**
 * Resolves a tenant's effective rate limits from its [TenantConfig], applying plan-based
 * fallback semantics (see #273):
 *  - OPEN_SOURCE is always unlimited — self-hosted installs are never rate-limited by this
 *    feature, even if the columns happen to be set.
 *  - CLOUD falls back to the Cloud-wide default for any dimension left unconfigured.
 *  - ENTERPRISE is unlimited by default (dedicated infra) and only honors dimensions the
 *    tenant explicitly configured — it never falls back to the CLOUD defaults, since
 *    "unlimited" is ENTERPRISE's default posture, not a gap to fill.
 */
object RateLimitPolicy {
    fun resolve(
        config: TenantConfig,
        cloudDefaultPerSecond: Int,
        cloudDefaultPerMinute: Int,
    ): RateLimitDecision =
        when (config.plan) {
            TenantPlan.OPEN_SOURCE -> {
                RateLimitDecision(null, null)
            }

            TenantPlan.CLOUD -> {
                RateLimitDecision(
                    perSecond = config.rateLimitPerSecond ?: cloudDefaultPerSecond,
                    perMinute = config.rateLimitPerMinute ?: cloudDefaultPerMinute,
                )
            }

            TenantPlan.ENTERPRISE -> {
                RateLimitDecision(
                    perSecond = config.rateLimitPerSecond,
                    perMinute = config.rateLimitPerMinute,
                )
            }
        }
}
