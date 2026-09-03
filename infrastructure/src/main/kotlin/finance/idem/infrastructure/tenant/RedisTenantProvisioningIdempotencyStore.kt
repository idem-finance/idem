package finance.idem.infrastructure.tenant

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import finance.idem.application.port.TenantProvisioningIdempotencyStore
import finance.idem.application.tenant.ProvisionedTenant
import finance.idem.core.TenantId
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Redis-backed idempotency for `POST /internal/admin/tenants` (#272 review finding: a
 * retried request with no Idempotency-Key silently double-provisions a tenant, key, and
 * welcome email). The raw API key is only ever available once (bcrypt hash only after
 * that), so a genuine "replay the exact same response" idempotency contract — the one
 * CLAUDE.md documents for every POST endpoint — means caching the full response here,
 * not just an id to re-fetch. The value sits in Redis plaintext for [REPLAY_TTL]; accepted
 * because this store only ever holds admin-token-trusted, internal-only traffic — the
 * same trust tier as the token that gates this endpoint in the first place.
 *
 * Two TTLs: a short [CLAIM_TTL] on the sentinel so a crashed mid-flight attempt doesn't
 * wedge a key for the full replay window, and a longer [REPLAY_TTL] once the real result
 * is cached via [cache].
 */
@Component
class RedisTenantProvisioningIdempotencyStore(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) : TenantProvisioningIdempotencyStore {
    override fun claim(key: String): Boolean = redisTemplate.opsForValue().setIfAbsent(redisKey(key), SENTINEL, CLAIM_TTL) ?: false

    override fun findCached(key: String): ProvisionedTenant? {
        val value = redisTemplate.opsForValue().get(redisKey(key)) ?: return null
        if (value == SENTINEL) return null
        val cached: CachedProvisionedTenant = objectMapper.readValue(value)
        return ProvisionedTenant(
            tenantId = TenantId.of(cached.tenantId),
            rawApiKey = cached.rawApiKey,
            dashboardUrl = cached.dashboardUrl,
        )
    }

    override fun cache(
        key: String,
        result: ProvisionedTenant,
    ) {
        val cached =
            CachedProvisionedTenant(
                tenantId = result.tenantId.value.toString(),
                rawApiKey = result.rawApiKey,
                dashboardUrl = result.dashboardUrl,
            )
        redisTemplate.opsForValue().set(redisKey(key), objectMapper.writeValueAsString(cached), REPLAY_TTL)
    }

    override fun release(key: String) {
        redisTemplate.delete(redisKey(key))
    }

    private fun redisKey(key: String) = "tenant-provisioning-idempotency:$key"

    private data class CachedProvisionedTenant(
        val tenantId: String,
        val rawApiKey: String,
        val dashboardUrl: String,
    )

    companion object {
        private const val SENTINEL = "IN_PROGRESS"
        private val CLAIM_TTL: Duration = Duration.ofMinutes(2)
        private val REPLAY_TTL: Duration = Duration.ofHours(1)
    }
}
