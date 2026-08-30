package finance.idem.infrastructure.persistence.tenant

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import finance.idem.core.TenantId
import finance.idem.core.tenant.TenantConfig
import finance.idem.core.tenant.TenantConfigRepository
import finance.idem.core.tenant.TenantPlan
import finance.idem.infrastructure.persistence.setRlsTenantId
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant

@Component
class TenantConfigRepositoryAdapter(
    private val jpaRepository: TenantJpaRepository,
    private val entityManager: EntityManager,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${idem.tenant-config.cache-ttl-minutes:10}") cacheTtlMinutes: Long,
) : TenantConfigRepository {
    private val cacheTtl = Duration.ofMinutes(cacheTtlMinutes)

    @Transactional(readOnly = true)
    override fun findByTenantId(tenantId: TenantId): TenantConfig? {
        val cacheKey = cacheKey(tenantId)
        redisTemplate.opsForValue().get(cacheKey)?.let { json ->
            return if (json == NOT_FOUND_MARKER) null else deserializeFromCache(json)
        }

        // Repopulating the cache here after a miss can race a concurrent invalidate() (from
        // BillingWebhookService) and re-cache a value that's stale by the time this SET lands.
        // upsert()'s own cache eviction now runs strictly after its DB commit (see below), so
        // the only remaining staleness window is bounded by cacheTtl and already best-effort.
        entityManager.setRlsTenantId(tenantId)
        val entity = jpaRepository.findById(tenantId.value).orElse(null)
        if (entity == null) {
            redisTemplate.opsForValue().set(cacheKey, NOT_FOUND_MARKER, cacheTtl)
            return null
        }
        val config = entity.toTenantConfig()
        redisTemplate.opsForValue().set(cacheKey, serializeForCache(config), cacheTtl)
        return config
    }

    @Transactional
    override fun upsert(config: TenantConfig) {
        entityManager.setRlsTenantId(config.tenantId)
        val existing = jpaRepository.findById(config.tenantId.value).orElse(null)
        val now = Instant.now()
        val updated =
            TenantDataModel(
                id = config.tenantId.value,
                webhookUrl = existing?.webhookUrl,
                webhookSecret = existing?.webhookSecret,
                createdAt = existing?.createdAt ?: config.createdAt,
                updatedAt = now,
                plan = config.plan.name,
                rateLimitPerSecond = config.rateLimitPerSecond,
                rateLimitPerMinute = config.rateLimitPerMinute,
                featureFlags = config.featureFlags.joinToString(","),
                hmacKey = config.hmacKey,
                billingCustomerId = config.billingCustomerId,
                suspendedAt = config.suspendedAt,
            )
        jpaRepository.save(updated)
        evictCacheAfterCommit(config.tenantId)
    }

    override fun invalidate(tenantId: TenantId) {
        redisTemplate.delete(cacheKey(tenantId))
    }

    /**
     * Defers the cache eviction until this transaction commits. Deleting immediately (while
     * the write is still uncommitted) lets a concurrent cache-miss reader repopulate the cache
     * with the pre-upsert value in the window before commit, where the eviction that was meant
     * to prevent exactly that has already fired and won't fire again.
     */
    private fun evictCacheAfterCommit(tenantId: TenantId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        redisTemplate.delete(cacheKey(tenantId))
                    }
                },
            )
        } else {
            redisTemplate.delete(cacheKey(tenantId))
        }
    }

    private fun cacheKey(tenantId: TenantId) = "tenantconfig:${tenantId.value}"

    private fun serializeForCache(config: TenantConfig): String = objectMapper.writeValueAsString(CachedEntry.from(config))

    private fun deserializeFromCache(json: String): TenantConfig = objectMapper.readValue<CachedEntry>(json).toTenantConfig()

    private fun TenantDataModel.toTenantConfig(): TenantConfig =
        TenantConfig(
            tenantId = TenantId(id),
            plan = TenantPlan.valueOf(plan),
            rateLimitPerSecond = rateLimitPerSecond,
            rateLimitPerMinute = rateLimitPerMinute,
            featureFlags = featureFlags.split(",").filter { it.isNotBlank() }.toSet(),
            hmacKey = hmacKey,
            billingCustomerId = billingCustomerId,
            createdAt = createdAt,
            suspendedAt = suspendedAt,
        )

    private data class CachedEntry(
        val tenantId: String,
        val plan: String,
        val rateLimitPerSecond: Int?,
        val rateLimitPerMinute: Int?,
        val featureFlags: Set<String>,
        val hmacKey: String?,
        val billingCustomerId: String?,
        val createdAt: Instant,
        val suspendedAt: Instant?,
    ) {
        fun toTenantConfig(): TenantConfig =
            TenantConfig(
                tenantId = TenantId.of(tenantId),
                plan = TenantPlan.valueOf(plan),
                rateLimitPerSecond = rateLimitPerSecond,
                rateLimitPerMinute = rateLimitPerMinute,
                featureFlags = featureFlags,
                hmacKey = hmacKey,
                billingCustomerId = billingCustomerId,
                createdAt = createdAt,
                suspendedAt = suspendedAt,
            )

        companion object {
            fun from(config: TenantConfig) =
                CachedEntry(
                    tenantId = config.tenantId.value.toString(),
                    plan = config.plan.name,
                    rateLimitPerSecond = config.rateLimitPerSecond,
                    rateLimitPerMinute = config.rateLimitPerMinute,
                    featureFlags = config.featureFlags,
                    hmacKey = config.hmacKey,
                    billingCustomerId = config.billingCustomerId,
                    createdAt = config.createdAt,
                    suspendedAt = config.suspendedAt,
                )
        }
    }

    companion object {
        private const val NOT_FOUND_MARKER = "__NOT_FOUND__"
    }
}
