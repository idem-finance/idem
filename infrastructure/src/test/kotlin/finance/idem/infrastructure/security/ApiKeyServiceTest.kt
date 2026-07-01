package finance.idem.infrastructure.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import finance.idem.core.TenantId
import finance.idem.core.security.ApiKey
import finance.idem.core.security.ApiKeyId
import finance.idem.core.security.ApiKeyRepository
import finance.idem.core.security.ApiScope
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class ApiKeyServiceTest {
    @Mock
    private lateinit var apiKeyRepository: ApiKeyRepository

    @Mock
    private lateinit var redisTemplate: StringRedisTemplate

    @Mock
    private lateinit var passwordEncoder: PasswordEncoder

    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val opsForValue: ValueOperations<String, String> = mock()

    private val tenantId = TenantId.generate()

    private val service by lazy {
        ApiKeyService(apiKeyRepository, redisTemplate, objectMapper, passwordEncoder)
    }

    @BeforeEach
    fun setup() {
        // Lenient: only validate() calls opsForValue(); generate/revoke tests don't
        Mockito.lenient().`when`(redisTemplate.opsForValue()).thenReturn(opsForValue)
    }

    @Test
    fun `generate creates key with sk_live prefix, hashes it, and saves`() {
        whenever(passwordEncoder.encode(any())).thenReturn("\$2a\$12\$hash")
        whenever(apiKeyRepository.save(any())).thenAnswer { it.arguments[0] as ApiKey }

        val (rawKey, apiKey) = service.generate(tenantId, setOf(ApiScope.TRANSACTIONS_READ))

        assertTrue(rawKey.startsWith("sk_live_"))
        assertEquals(rawKey.take(12), apiKey.prefix)
        assertEquals("\$2a\$12\$hash", apiKey.keyHash)
        assertEquals(setOf(ApiScope.TRANSACTIONS_READ), apiKey.scopes)
        assertFalse(apiKey.isRevoked)
        verify(apiKeyRepository).save(any())
    }

    @Test
    fun `validate returns null when prefix not in cache and not in DB`() {
        whenever(opsForValue.get(any())).thenReturn(null)
        whenever(apiKeyRepository.findByPrefix(any())).thenReturn(null)

        assertNull(service.validate("sk_live_testabcd1234"))
        verify(apiKeyRepository).findByPrefix("sk_live_test")
    }

    @Test
    fun `validate returns null for revoked key without checking hash`() {
        val revokedKey = apiKey().copy(revokedAt = Instant.now())
        whenever(opsForValue.get(any())).thenReturn(null)
        whenever(apiKeyRepository.findByPrefix(any())).thenReturn(revokedKey)

        assertNull(service.validate("sk_live_testabcd1234"))
        verify(passwordEncoder, never()).matches(any(), any())
    }

    @Test
    fun `validate returns null when hash does not match`() {
        whenever(opsForValue.get(any())).thenReturn(null)
        whenever(apiKeyRepository.findByPrefix(any())).thenReturn(apiKey())
        whenever(passwordEncoder.matches(any(), any())).thenReturn(false)

        assertNull(service.validate("sk_live_testabcd1234"))
    }

    @Test
    fun `validate returns ValidatedApiKey from cache without hitting DB`() {
        val cached = """{"tenantId":"${tenantId.value}","scopes":["TRANSACTIONS_READ"]}"""
        whenever(opsForValue.get("apikey:sk_live_test")).thenReturn(cached)

        val result = service.validate("sk_live_testabcd1234")

        assertNotNull(result)
        assertEquals(tenantId, result.tenantId)
        assertTrue(result.scopes.contains(ApiScope.TRANSACTIONS_READ))
        verify(apiKeyRepository, never()).findByPrefix(any())
    }

    @Test
    fun `validate caches result and returns ValidatedApiKey from DB`() {
        val key = apiKey()
        whenever(opsForValue.get(any())).thenReturn(null)
        whenever(apiKeyRepository.findByPrefix(any())).thenReturn(key)
        whenever(passwordEncoder.matches(any(), any())).thenReturn(true)

        val result = service.validate("sk_live_testabcd1234")

        assertNotNull(result)
        assertEquals(tenantId, result.tenantId)
        verify(opsForValue).set(eq("apikey:sk_live_test"), any(), eq(Duration.ofMinutes(5)))
    }

    @Test
    fun `revoke returns false for key not found`() {
        val keyId = ApiKeyId.generate()
        whenever(apiKeyRepository.findById(keyId, tenantId)).thenReturn(null)

        assertFalse(service.revoke(keyId, tenantId))
        verify(redisTemplate, never()).delete(any<String>())
    }

    @Test
    fun `revoke saves with revokedAt and evicts cache entry`() {
        val key = apiKey()
        whenever(apiKeyRepository.findById(key.id, tenantId)).thenReturn(key)
        whenever(apiKeyRepository.save(any())).thenAnswer { it.arguments[0] as ApiKey }

        val result = service.revoke(key.id, tenantId)

        assertTrue(result)
        val captor = argumentCaptor<ApiKey>()
        verify(apiKeyRepository).save(captor.capture())
        assertNotNull(captor.firstValue.revokedAt)
        verify(redisTemplate).delete("apikey:${key.prefix}")
    }

    private fun apiKey() =
        ApiKey(
            id = ApiKeyId.generate(),
            tenantId = tenantId,
            keyHash = "\$2a\$12\$fakehash",
            prefix = "sk_live_test",
            scopes = setOf(ApiScope.TRANSACTIONS_READ),
            createdAt = Instant.now(),
        )
}
