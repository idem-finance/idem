package finance.idem.infrastructure.persistence.tenant

import finance.idem.core.TenantId
import finance.idem.core.tenant.TenantConfig
import finance.idem.core.tenant.TenantPlan
import finance.idem.infrastructure.SharedPostgresTestBase
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @MockitoSpyBean
    private lateinit var jpaRepository: TenantJpaRepository

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
    fun `isolates config between tenants`() {
        val tenantA = TenantId.generate()
        val tenantB = TenantId.generate()
        adapter.upsert(config(tenantA, plan = TenantPlan.OPEN_SOURCE))
        adapter.upsert(config(tenantB, plan = TenantPlan.ENTERPRISE))

        assertEquals(TenantPlan.OPEN_SOURCE, adapter.findByTenantId(tenantA)?.plan)
        assertEquals(TenantPlan.ENTERPRISE, adapter.findByTenantId(tenantB)?.plan)
    }

    @Test
    fun `upsert cache eviction is visible to an immediate subsequent read (after-commit ordering)`() {
        val tenantId = TenantId.generate()
        adapter.upsert(config(tenantId, plan = TenantPlan.OPEN_SOURCE))
        adapter.findByTenantId(tenantId) // populate cache with OPEN_SOURCE

        adapter.upsert(config(tenantId, plan = TenantPlan.CLOUD))
        // No interleaving reader here — this asserts the eviction from this upsert's own
        // commit already landed by the time upsert() returns, not merely "eventually".
        val found = adapter.findByTenantId(tenantId)

        assertEquals(TenantPlan.CLOUD, found?.plan)
    }

    @Test
    fun `concurrent read during upsert converges to the latest value once both complete`() {
        val tenantId = TenantId.generate()
        adapter.upsert(config(tenantId, plan = TenantPlan.OPEN_SOURCE))
        adapter.findByTenantId(tenantId) // populate cache with OPEN_SOURCE

        val executor = Executors.newFixedThreadPool(2)
        try {
            val readerDone = executor.submit { adapter.findByTenantId(tenantId) }
            val writerDone = executor.submit { adapter.upsert(config(tenantId, plan = TenantPlan.CLOUD)) }
            readerDone.get(10, TimeUnit.SECONDS)
            writerDone.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdown()
        }

        assertEquals(TenantPlan.CLOUD, adapter.findByTenantId(tenantId)?.plan)
    }

    @Test
    fun `findByTenantId negatively caches a missing tenant — second call skips the DB`() {
        val tenantId = TenantId.generate()
        Mockito.clearInvocations(jpaRepository)

        assertNull(adapter.findByTenantId(tenantId))
        assertNull(adapter.findByTenantId(tenantId))

        Mockito.verify(jpaRepository, Mockito.times(1)).findById(tenantId.value)
    }

    @Test
    fun `upsert evicts the negative cache so a tenant that gains a config is found immediately`() {
        val tenantId = TenantId.generate()
        assertNull(adapter.findByTenantId(tenantId)) // populates the negative cache marker

        adapter.upsert(config(tenantId, plan = TenantPlan.CLOUD))
        val found = adapter.findByTenantId(tenantId)

        assertEquals(TenantPlan.CLOUD, found?.plan)
    }

    @Test
    fun `cache-hit round-trip preserves a multi-element featureFlags set`() {
        val tenantId = TenantId.generate()
        val flags = setOf("compliance_export", "webhook_retry_v2", "mcp_rollback")
        adapter.upsert(config(tenantId, featureFlags = flags))
        adapter.findByTenantId(tenantId) // populate cache

        val found = adapter.findByTenantId(tenantId) // served from cache

        assertEquals(flags, found?.featureFlags)
    }
}
