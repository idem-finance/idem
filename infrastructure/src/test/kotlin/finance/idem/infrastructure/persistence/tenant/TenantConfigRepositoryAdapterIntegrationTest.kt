package finance.idem.infrastructure.persistence.tenant

import finance.idem.core.TenantId
import finance.idem.core.tenant.TenantConfig
import finance.idem.core.tenant.TenantPlan
import finance.idem.infrastructure.SharedPostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class TenantConfigRepositoryAdapterIntegrationTest : SharedPostgresTestBase() {
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
    private lateinit var adapter: TenantConfigRepositoryAdapter

    private fun config(
        tenantId: TenantId,
        plan: TenantPlan = TenantPlan.CLOUD,
        rateLimitPerSecond: Int? = 5,
        featureFlags: Set<String> = setOf("compliance_export"),
        hmacKey: String? = "tenant-hmac-key",
    ) = TenantConfig(
        tenantId = tenantId,
        plan = plan,
        rateLimitPerSecond = rateLimitPerSecond,
        rateLimitPerMinute = 300,
        featureFlags = featureFlags,
        hmacKey = hmacKey,
        billingCustomerId = "cus_123",
        // Postgres TIMESTAMPTZ stores microsecond precision — truncate here so the
        // round-tripped value compares equal instead of differing in trailing nanos.
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
        suspendedAt = null,
    )

    @Test
    fun `findByTenantId returns null when no row exists`() {
        assertNull(adapter.findByTenantId(TenantId.generate()))
    }

    @Test
    fun `upsert then findByTenantId — cache miss reads through to DB and round-trips all fields`() {
        val tenantId = TenantId.generate()
        val saved = config(tenantId)

        adapter.upsert(saved)
        val found = adapter.findByTenantId(tenantId)

        assertEquals(saved, found)
    }

    @Test
    fun `findByTenantId second call is served from cache without new field drift`() {
        val tenantId = TenantId.generate()
        adapter.upsert(config(tenantId))

        val first = adapter.findByTenantId(tenantId)
        val second = adapter.findByTenantId(tenantId)

        assertEquals(first, second)
    }

    @Test
    fun `upsert evicts stale cache entry so a later read reflects the update`() {
        val tenantId = TenantId.generate()
        adapter.upsert(config(tenantId, plan = TenantPlan.OPEN_SOURCE))
        adapter.findByTenantId(tenantId) // populate cache with OPEN_SOURCE

        adapter.upsert(config(tenantId, plan = TenantPlan.ENTERPRISE))
        val found = adapter.findByTenantId(tenantId)

        assertEquals(TenantPlan.ENTERPRISE, found?.plan)
    }

    @Test
    fun `invalidate evicts cache so a later read hits the DB again`() {
        val tenantId = TenantId.generate()
        adapter.upsert(config(tenantId, rateLimitPerSecond = 5))
        adapter.findByTenantId(tenantId) // populate cache

        // Simulate an out-of-band DB update the cache doesn't know about yet.
        adapter.invalidate(tenantId)
        val found = adapter.findByTenantId(tenantId)

        assertTrue(found != null)
        assertEquals(5, found.rateLimitPerSecond)
    }

    @Test
    fun `isolates config between tenants`() {
        val tenantA = TenantId.generate()
        val tenantB = TenantId.generate()
        adapter.upsert(config(tenantA, plan = TenantPlan.OPEN_SOURCE))
        adapter.upsert(config(tenantB, plan = TenantPlan.ENTERPRISE))

        assertEquals(TenantPlan.OPEN_SOURCE, adapter.findByTenantId(tenantA)?.plan)
        assertEquals(TenantPlan.ENTERPRISE, adapter.findByTenantId(tenantB)?.plan)
    }
}
