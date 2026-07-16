package finance.idem.infrastructure.security

import finance.idem.core.TenantId
import finance.idem.core.security.ApiScope
import finance.idem.infrastructure.SharedPostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ApiKeyServiceIntegrationTest : SharedPostgresTestBase() {
    companion object {
        // Redis has no module-wide singleton — this is the only infra test that needs it.
        @Container
        val redis: GenericContainer<*> =
            GenericContainer("redis:7")
                .withExposedPorts(6379)

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
        }
    }

    @Autowired
    private lateinit var apiKeyService: ApiKeyService

    private val tenantId = TenantId.generate()

    @Test
    fun `full lifecycle — generate, validate cache-miss, cache-hit, revoke, validate returns null`() {
        val scopes = setOf(ApiScope.TRANSACTIONS_READ, ApiScope.ACCOUNTS_READ)

        // 1. generate — key persisted, raw key returned
        val (rawKey, apiKey) = apiKeyService.generate(tenantId, scopes)
        assertTrue(rawKey.startsWith("sk_live_"))
        assertFalse(apiKey.isRevoked)

        // 2. validate — cache miss, DB lookup, cache populated
        val validated = apiKeyService.validate(rawKey)
        assertNotNull(validated)
        assertTrue(validated.hasScope(ApiScope.TRANSACTIONS_READ))
        assertTrue(validated.hasScope(ApiScope.ACCOUNTS_READ))
        assertFalse(validated.hasScope(ApiScope.ADMIN))

        // 3. validate again — cache hit (no new DB query)
        val validatedAgain = apiKeyService.validate(rawKey)
        assertNotNull(validatedAgain)
        assertTrue(validatedAgain.hasScope(ApiScope.TRANSACTIONS_READ))

        // 4. revoke — sets revokedAt, evicts cache
        val revoked = apiKeyService.revoke(apiKey.id, tenantId)
        assertTrue(revoked)

        // 5. validate after revoke — returns null (cache evicted, DB shows revoked)
        val afterRevoke = apiKeyService.validate(rawKey)
        assertNull(afterRevoke)
    }

    @Test
    fun `validate with wrong raw key returns null`() {
        val (_, apiKey) = apiKeyService.generate(tenantId, setOf(ApiScope.TRANSACTIONS_READ))

        // Use correct prefix but wrong trailing chars
        val tamperedKey = apiKey.prefix + "0000000000000000000000000000"
        assertNull(apiKeyService.validate(tamperedKey))
    }

    @Test
    fun `revoke with wrong tenant returns false`() {
        val (_, apiKey) = apiKeyService.generate(tenantId, setOf(ApiScope.TRANSACTIONS_READ))
        val otherTenant = TenantId.generate()

        assertFalse(apiKeyService.revoke(apiKey.id, otherTenant))
    }
}
