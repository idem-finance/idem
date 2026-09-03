package finance.idem.infrastructure.ratelimit

import finance.idem.core.TenantId
import finance.idem.core.tenant.RateLimitDecision
import finance.idem.core.tenant.RateLimitPolicy
import finance.idem.core.tenant.TenantConfig
import finance.idem.core.tenant.TenantConfigRepository
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.function.Supplier

sealed interface RateLimitResult {
    /** No tenant-level limit applies (OPEN_SOURCE, or ENTERPRISE with no configured limits). */
    data object Unlimited : RateLimitResult

    data object Allowed : RateLimitResult

    data class Denied(
        val retryAfterSeconds: Long,
    ) : RateLimitResult
}

/**
 * Enforces the per-tenant token bucket described in #273: one Redis-backed bucket per
 * tenant with up to two simultaneous bandwidths (burst per-second, sustained per-minute),
 * sized from [TenantConfig] via [RateLimitPolicy].
 */
@Component
@ConditionalOnProperty(name = ["idem.ratelimit.enabled"], havingValue = "true")
class RateLimiterService(
    private val proxyManager: LettuceBasedProxyManager<ByteArray>,
    private val tenantConfigRepository: TenantConfigRepository,
    private val properties: RateLimitProperties,
) {
    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L
    }

    fun tryConsume(tenantId: TenantId): RateLimitResult {
        val config = tenantConfigRepository.findByTenantId(tenantId) ?: TenantConfig.default(tenantId)
        val decision = RateLimitPolicy.resolve(config, properties.cloudDefaultPerSecond, properties.cloudDefaultPerMinute)
        if (decision.isUnlimited) return RateLimitResult.Unlimited

        val key = bucketKey(tenantId, decision)
        val bucket = proxyManager.getProxy(key, Supplier { buildConfiguration(decision) })
        val probe = bucket.tryConsumeAndReturnRemaining(1)

        return if (probe.isConsumed) {
            RateLimitResult.Allowed
        } else {
            // Round up to the next whole second so callers never advise a Retry-After
            // shorter than the actual remaining wait.
            val retryAfterSeconds = (probe.nanosToWaitForRefill + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND
            RateLimitResult.Denied(retryAfterSeconds)
        }
    }

    // Bucket4j's Lettuce CAS proxy manager doesn't reliably reconcile an in-place
    // replaceConfiguration() for a bucket with more than one bandwidth (verified empirically
    // against bucket4j 8.14.0: a multi-bandwidth reconfiguration silently discards the
    // bandwidths' actual consumption history instead of scaling/preserving it, regardless of
    // TokensInheritanceStrategy) — so rather than mutate a persisted bucket in place, the
    // limit values are folded into the key itself. A TenantConfig change (#273 finding) then
    // takes effect on the very next request simply because it addresses a different key; the
    // old key is left to expire via the proxy manager's expirationAfterWrite (see
    // RateLimitRedisConfig) rather than being reconciled.
    private fun bucketKey(
        tenantId: TenantId,
        decision: RateLimitDecision,
    ): ByteArray = "ratelimit:${tenantId.value}:${decision.perSecond}:${decision.perMinute}".toByteArray(StandardCharsets.UTF_8)

    private fun buildConfiguration(decision: RateLimitDecision): BucketConfiguration {
        val builder = BucketConfiguration.builder()
        decision.perSecond?.let { limit ->
            builder.addLimit { it.capacity(limit.toLong()).refillGreedy(limit.toLong(), Duration.ofSeconds(1)) }
        }
        decision.perMinute?.let { limit ->
            builder.addLimit { it.capacity(limit.toLong()).refillGreedy(limit.toLong(), Duration.ofMinutes(1)) }
        }
        return builder.build()
    }
}
