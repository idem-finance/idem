package finance.idem.infrastructure.security

import finance.idem.core.TenantId
import finance.idem.core.security.ApiKey
import finance.idem.core.security.ApiKeyId
import finance.idem.core.security.ApiScope
import finance.idem.infrastructure.SharedPostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ApiKeyRepositoryAdapter::class)
class ApiKeyRepositoryAdapterTest : SharedPostgresTestBase() {
    companion object {
        private val prefixSeq = AtomicInteger(0)
    }

    @Autowired
    lateinit var adapter: ApiKeyRepositoryAdapter

    private val tenantId = TenantId.generate()
    private val now = Instant.now()

    @Test
    fun `save and findAllByPrefix round-trip preserves all fields`() {
        val key = apiKey(prefix = "sk_live_aabb")
        adapter.save(key)

        val found = adapter.findAllByPrefix("sk_live_aabb").single()

        assertEquals(key.id, found.id)
        assertEquals(key.tenantId, found.tenantId)
        assertEquals(key.keyHash, found.keyHash)
        assertEquals(key.prefix, found.prefix)
        assertEquals(key.scopes, found.scopes)
        assertEquals(key.createdAt.epochSecond, found.createdAt.epochSecond)
        assertNull(found.revokedAt)
    }

    @Test
    fun `findAllByPrefix returns empty for unknown prefix`() {
        assertTrue(adapter.findAllByPrefix("sk_live_none").isEmpty())
    }

    @Test
    fun `findAllByPrefix returns every key sharing a prefix`() {
        val first = apiKey(prefix = "sk_live_coll")
        val second = apiKey(prefix = "sk_live_coll").copy(id = ApiKeyId.generate())
        adapter.save(first)
        adapter.save(second)

        val found = adapter.findAllByPrefix("sk_live_coll")

        assertEquals(setOf(first.id, second.id), found.map { it.id }.toSet())
    }

    @Test
    fun `save with revoked_at persists revocation`() {
        val revokedAt = Instant.now()
        val key = apiKey().copy(revokedAt = revokedAt)
        adapter.save(key)

        val found = adapter.findAllByPrefix(key.prefix).single()
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

        val found = adapter.findAllByPrefix(key.prefix).single()
        assertEquals(scopes, found.scopes)
    }

    @Test
    fun `empty scopes round-trips correctly`() {
        val key = apiKey(scopes = emptySet())
        adapter.save(key)

        val found = adapter.findAllByPrefix(key.prefix).single()
        assertTrue(found.scopes.isEmpty())
    }

    @Test
    fun `findAllByTenantId returns all keys for tenant including revoked`() {
        val active = apiKey()
        val revoked = apiKey().copy(revokedAt = Instant.now())
        adapter.save(active)
        adapter.save(revoked)

        val results = adapter.findAllByTenantId(tenantId)

        val ids = results.map { it.id }.toSet()
        assertTrue(ids.contains(active.id))
        assertTrue(ids.contains(revoked.id))
    }

    @Test
    fun `findAllByTenantId excludes keys of other tenants`() {
        val myKey = apiKey()
        adapter.save(myKey)

        val otherTenantKey =
            apiKey().copy(
                id = ApiKeyId.generate(),
                tenantId = TenantId.generate(),
            )
        adapter.save(
            ApiKey(
                id = ApiKeyId.generate(),
                tenantId = TenantId.generate(),
                keyHash = "\$2a\$12\$other",
                prefix = "sk_test_othr",
                scopes = setOf(ApiScope.TRANSACTIONS_READ),
                createdAt = Instant.now(),
            ),
        )

        val results = adapter.findAllByTenantId(tenantId)
        assertTrue(results.none { it.tenantId != tenantId })
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
