package finance.idem.infrastructure.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import finance.idem.core.TenantId
import finance.idem.core.security.ApiKey
import finance.idem.core.security.ApiKeyId
import finance.idem.core.security.ApiKeyRepository
import finance.idem.core.security.ApiScope
import finance.idem.core.security.ValidatedApiKey
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class ApiKeyService(
    private val apiKeyRepository: ApiKeyRepository,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val passwordEncoder: PasswordEncoder,
) {
    private val cacheTtl = Duration.ofMinutes(5)
    private val log = LoggerFactory.getLogger(ApiKeyService::class.java)

    @Transactional
    fun generate(tenantId: TenantId, scopes: Set<ApiScope>): Pair<String, ApiKey> {
        val rawKey = "sk_live_${UUID.randomUUID().toString().replace("-", "")}"
        val prefix = rawKey.take(12)
        val keyHash = passwordEncoder.encode(rawKey)
        val apiKey = ApiKey.create(
            tenantId = tenantId,
            keyHash = keyHash,
            prefix = prefix,
            scopes = scopes,
        )
        apiKeyRepository.save(apiKey)
        return rawKey to apiKey
    }

    fun validate(rawKey: String): ValidatedApiKey? {
        val prefix = rawKey.take(12)
        val cacheKey = cacheKey(prefix)

        redisTemplate.opsForValue().get(cacheKey)?.let { json ->
            deserializeFromCache(json)?.let { return it }
        }

        val apiKey = apiKeyRepository.findByPrefix(prefix) ?: return null
        if (apiKey.isRevoked) return null
        if (!passwordEncoder.matches(rawKey, apiKey.keyHash)) {
            log.warn("Hash mismatch for prefix={}***", prefix.take(6))
            return null
        }

        val validated = ValidatedApiKey(apiKey.tenantId, apiKey.scopes)
        redisTemplate.opsForValue().set(cacheKey, serializeForCache(validated), cacheTtl)
        return validated
    }

    @Transactional
    fun revoke(keyId: ApiKeyId, tenantId: TenantId): Boolean {
        val apiKey = apiKeyRepository.findById(keyId, tenantId) ?: return false
        apiKeyRepository.save(apiKey.copy(revokedAt = Instant.now()))
        redisTemplate.delete(cacheKey(apiKey.prefix))
        return true
    }

    private fun cacheKey(prefix: String) = "apikey:$prefix"

    private fun serializeForCache(validated: ValidatedApiKey): String =
        objectMapper.writeValueAsString(
            CachedEntry(
                tenantId = validated.tenantId.value.toString(),
                scopes = validated.scopes.map { it.name },
            )
        )

    private fun deserializeFromCache(json: String): ValidatedApiKey? {
        val cached: CachedEntry = objectMapper.readValue(json)
        val scopes = cached.scopes.mapNotNullTo(mutableSetOf()) { name ->
            runCatching { ApiScope.valueOf(name) }.getOrNull()
        }
        // Unknown scope name means cache entry is stale (e.g. post-migration rename) — force DB re-validation.
        if (scopes.size != cached.scopes.size) return null
        return ValidatedApiKey(
            tenantId = TenantId.of(cached.tenantId),
            scopes = scopes,
        )
    }

    private data class CachedEntry(val tenantId: String, val scopes: List<String>)
}
