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
            return deserializeFromCache(json)
        }

        entityManager.setRlsTenantId(tenantId)
        val config = jpaRepository.findById(tenantId.value).orElse(null)?.toTenantConfig() ?: return null
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
        redisTemplate.delete(cacheKey(config.tenantId))
    }

    override fun invalidate(tenantId: TenantId) {
        redisTemplate.delete(cacheKey(tenantId))
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
}
