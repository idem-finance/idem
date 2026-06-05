package finance.idem.infrastructure.security

import finance.idem.core.TenantId
import finance.idem.core.security.ApiKey
import finance.idem.core.security.ApiKeyId
import finance.idem.core.security.ApiScope
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(ApiKeyRepositoryAdapter::class)
class ApiKeyRepositoryAdapterTest {

    companion object {
        private val prefixSeq = AtomicInteger(0)

        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
            .withDatabaseName("idem_test")
            .withUsername("idem")
            .withPassword("idem")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired
    lateinit var adapter: ApiKeyRepositoryAdapter

    private val tenantId = TenantId.generate()
    private val now = Instant.now()

    @Test
    fun `save and findByPrefix round-trip preserves all fields`() {
        val key = apiKey(prefix = "sk_live_aabb")
        adapter.save(key)

        val found = adapter.findByPrefix("sk_live_aabb")

        assertNotNull(found)
        assertEquals(key.id, found.id)
        assertEquals(key.tenantId, found.tenantId)
        assertEquals(key.keyHash, found.keyHash)
        assertEquals(key.prefix, found.prefix)
        assertEquals(key.scopes, found.scopes)
        assertEquals(key.createdAt.epochSecond, found.createdAt.epochSecond)
        assertNull(found.revokedAt)
    }

    @Test
    fun `findByPrefix returns null for unknown prefix`() {
        assertNull(adapter.findByPrefix("sk_live_none"))
    }

    @Test
    fun `save with revoked_at persists revocation`() {
        val revokedAt = Instant.now()
        val key = apiKey().copy(revokedAt = revokedAt)
        adapter.save(key)

        val found = adapter.findByPrefix(key.prefix)
        assertNotNull(found)
        assertTrue(found.isRevoked)
        assertEquals(revokedAt.epochSecond, found.revokedAt!!.epochSecond)
    }

    @Test
    fun `findById returns key for correct tenant`() {
        val key = apiKey()
        adapter.save(key)

        val found = adapter.findById(key.id, tenantId)
        assertNotNull(found)
        assertEquals(key.id, found.id)
    }

    @Test
    fun `findById returns null for wrong tenant`() {
        val key = apiKey()
        adapter.save(key)

        val otherTenant = TenantId.generate()
        assertNull(adapter.findById(key.id, otherTenant))
    }

    @Test
    fun `scopes round-trip preserves multiple scopes`() {
        val scopes = setOf(ApiScope.TRANSACTIONS_READ, ApiScope.ACCOUNTS_READ, ApiScope.WEBHOOK_MANAGE)
        val key = apiKey(scopes = scopes)
        adapter.save(key)

        val found = adapter.findByPrefix(key.prefix)
        assertNotNull(found)
        assertEquals(scopes, found.scopes)
    }

    @Test
    fun `empty scopes round-trips correctly`() {
        val key = apiKey(scopes = emptySet())
        adapter.save(key)

        val found = adapter.findByPrefix(key.prefix)
        assertNotNull(found)
        assertTrue(found.scopes.isEmpty())
    }

    private fun apiKey(
        prefix: String = "sk_test_%04d".format(prefixSeq.incrementAndGet()),
        scopes: Set<ApiScope> = setOf(ApiScope.TRANSACTIONS_READ),
    ) = ApiKey(
        id = ApiKeyId.generate(),
        tenantId = tenantId,
        keyHash = "\$2a\$12\$fakehashfortest",
        prefix = prefix,
        scopes = scopes,
        createdAt = now,
    )
}
