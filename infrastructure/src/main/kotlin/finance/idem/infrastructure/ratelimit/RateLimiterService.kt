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

        val key = "ratelimit:${tenantId.value}".toByteArray(StandardCharsets.UTF_8)
        val bucket = proxyManager.getProxy(key, configurationSupplier(decision))
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

    private fun configurationSupplier(decision: RateLimitDecision): Supplier<BucketConfiguration> =
        Supplier {
            val builder = BucketConfiguration.builder()
            decision.perSecond?.let { limit ->
                builder.addLimit { it.capacity(limit.toLong()).refillGreedy(limit.toLong(), Duration.ofSeconds(1)) }
            }
            decision.perMinute?.let { limit ->
                builder.addLimit { it.capacity(limit.toLong()).refillGreedy(limit.toLong(), Duration.ofMinutes(1)) }
            }
            builder.build()
        }
}
