package finance.idem.infrastructure.tenant

import finance.idem.application.tenant.ProvisionedTenant
import finance.idem.core.TenantId
import finance.idem.infrastructure.SharedPostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class RedisTenantProvisioningIdempotencyStoreTest : SharedPostgresTestBase() {
    companion object {
        // Redis has no module-wide singleton — mirrors ApiKeyServiceIntegrationTest.
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
    private lateinit var store: RedisTenantProvisioningIdempotencyStore

    @Test
    fun `claim succeeds for a fresh key and fails for an already-claimed one`() {
        val key = "key-${TenantId.generate().value}"

        assertTrue(store.claim(key))
        assertFalse(store.claim(key))
    }

    @Test
    fun `findCached returns null while a claim is unresolved, then the cached result once cache() is called`() {
        val key = "key-${TenantId.generate().value}"
        val result = ProvisionedTenant(TenantId.generate(), "sk_live_test", "https://cloud.idem.finance/t/x")

        store.claim(key)
        assertNull(store.findCached(key))

        store.cache(key, result)
        assertEquals(result, store.findCached(key))
    }

    @Test
    fun `release frees the key for an immediate re-claim`() {
        val key = "key-${TenantId.generate().value}"

        store.claim(key)
        store.release(key)

        assertTrue(store.claim(key))
    }
}
