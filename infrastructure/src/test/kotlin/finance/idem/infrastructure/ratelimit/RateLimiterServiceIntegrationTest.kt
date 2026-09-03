package finance.idem.infrastructure.ratelimit

import finance.idem.core.TenantId
import finance.idem.core.tenant.TenantConfig
import finance.idem.core.tenant.TenantConfigRepository
import finance.idem.core.tenant.TenantPlan
import finance.idem.infrastructure.SharedPostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class RateLimiterServiceIntegrationTest : SharedPostgresTestBase() {
    companion object {
        // Redis has no module-wide singleton — mirrors ApiKeyServiceIntegrationTest /
        // TenantConfigRepositoryAdapterIntegrationTest.
        @Container
        val redis: GenericContainer<*> =
            GenericContainer("redis:7")
                .withExposedPorts(6379)

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
            // The whole feature (and its beans) is @ConditionalOnProperty-gated off by default.
            registry.add("idem.ratelimit.enabled") { "true" }
            registry.add("idem.ratelimit.cloud-default-per-second") { "100" }
            registry.add("idem.ratelimit.cloud-default-per-minute") { "1000" }
        }
    }

    @Autowired
    private lateinit var rateLimiterService: RateLimiterService

    @Autowired
    private lateinit var tenantConfigRepository: TenantConfigRepository

    @Autowired
    private lateinit var stringRedisTemplate: StringRedisTemplate

    private fun cloudConfig(
        tenantId: TenantId,
        rateLimitPerSecond: Int? = null,
        rateLimitPerMinute: Int? = null,
    ) = TenantConfig(
        tenantId = tenantId,
        plan = TenantPlan.CLOUD,
        rateLimitPerSecond = rateLimitPerSecond,
        rateLimitPerMinute = rateLimitPerMinute,
        featureFlags = emptySet(),
        hmacKey = null,
        billingCustomerId = null,
        createdAt = Instant.now(),
        suspendedAt = null,
    )

    private fun enterpriseConfig(
        tenantId: TenantId,
        rateLimitPerSecond: Int? = null,
        rateLimitPerMinute: Int? = null,
    ) = TenantConfig(
        tenantId = tenantId,
        plan = TenantPlan.ENTERPRISE,
        rateLimitPerSecond = rateLimitPerSecond,
        rateLimitPerMinute = rateLimitPerMinute,
        featureFlags = emptySet(),
        hmacKey = null,
        billingCustomerId = null,
        createdAt = Instant.now(),
        suspendedAt = null,
    )

    @Test
    fun `CLOUD tenant with an explicit per-second limit is allowed up to the limit then denied`() {
        val tenantId = TenantId.generate()
        tenantConfigRepository.upsert(cloudConfig(tenantId, rateLimitPerSecond = 3))

        val results = (1..4).map { rateLimiterService.tryConsume(tenantId) }

        assertEquals(3, results.count { it == RateLimitResult.Allowed })
        val denied = results.last()
        assertTrue(denied is RateLimitResult.Denied)
        assertTrue(denied.retryAfterSeconds > 0)
    }

    @Test
    fun `ENTERPRISE tenant with no configured limits is unlimited and never touches Redis`() {
        val tenantId = TenantId.generate()
        tenantConfigRepository.upsert(enterpriseConfig(tenantId))

        val results = (1..200).map { rateLimiterService.tryConsume(tenantId) }

        assertTrue(results.all { it == RateLimitResult.Unlimited })
        assertTrue(stringRedisTemplate.keys("ratelimit:${tenantId.value}*").isEmpty())
    }

    @Test
    fun `OPEN_SOURCE tenant (no TenantConfig row) is unlimited`() {
        val tenantId = TenantId.generate()

        val results = (1..50).map { rateLimiterService.tryConsume(tenantId) }

        assertTrue(results.all { it == RateLimitResult.Unlimited })
    }

    @Test
    fun `exhausting one tenant's bucket does not affect another tenant`() {
        val tenantA = TenantId.generate()
        val tenantB = TenantId.generate()
        tenantConfigRepository.upsert(cloudConfig(tenantA, rateLimitPerSecond = 2))
        tenantConfigRepository.upsert(cloudConfig(tenantB, rateLimitPerSecond = 2))

        repeat(2) { rateLimiterService.tryConsume(tenantA) }
        val tenantADenied = rateLimiterService.tryConsume(tenantA)
        val tenantBAllowed = rateLimiterService.tryConsume(tenantB)

        assertTrue(tenantADenied is RateLimitResult.Denied)
        assertEquals(RateLimitResult.Allowed, tenantBAllowed)
    }

    @Test
    fun `concurrent requests for the same tenant are serialized — allowed count never overshoots the limit beyond greedy drip`() {
        val tenantId = TenantId.generate()
        val limit = 10
        tenantConfigRepository.upsert(cloudConfig(tenantId, rateLimitPerSecond = limit))

        val executor = Executors.newFixedThreadPool(20)
        val results =
            try {
                val futures = (1..30).map { executor.submit<RateLimitResult> { rateLimiterService.tryConsume(tenantId) } }
                futures.map { it.get(10, TimeUnit.SECONDS) }
            } finally {
                executor.shutdown()
            }

        // Redis-side atomicity is what's under test here, not exact timing: bandwidths use
        // refillGreedy (tokens drip in continuously, by design — see RateLimiterService), so
        // if this batch's wall-clock execution happens to straddle a 1-second boundary, a
        // token or two can legitimately refill mid-run. What atomicity guarantees is that the
        // allowed count never overshoots by more than that small drip margin — nowhere near
        // all 30 concurrent requests succeeding, which is what a race in the CAS would allow.
        val allowedCount = results.count { it == RateLimitResult.Allowed }
        assertTrue(allowedCount in limit..(limit + 2), "expected allowedCount in $limit..${limit + 2}, was $allowedCount")
        assertEquals(30, results.size)
        assertTrue(results.count { it is RateLimitResult.Denied } > 0)
    }

    @Test
    fun `a tenant's Redis bucket key carries a TTL once created, bounding key growth`() {
        val tenantId = TenantId.generate()
        tenantConfigRepository.upsert(cloudConfig(tenantId, rateLimitPerSecond = 5))

        rateLimiterService.tryConsume(tenantId)

        val keys = stringRedisTemplate.keys("ratelimit:${tenantId.value}*")
        assertEquals(1, keys.size)
        val ttlSeconds = stringRedisTemplate.getExpire(keys.first(), TimeUnit.SECONDS)
        assertTrue(ttlSeconds > 0, "expected a positive TTL on the bucket key, was $ttlSeconds")
    }

    @Test
    fun `raising a tenant's limit is enforced on the very next request, not stuck on a stale exhausted bucket`() {
        val tenantId = TenantId.generate()
        tenantConfigRepository.upsert(cloudConfig(tenantId, rateLimitPerSecond = 1))
        // Exhaust the tight limit.
        assertEquals(RateLimitResult.Allowed, rateLimiterService.tryConsume(tenantId))
        assertTrue(rateLimiterService.tryConsume(tenantId) is RateLimitResult.Denied)

        tenantConfigRepository.upsert(cloudConfig(tenantId, rateLimitPerSecond = 5))

        // The bucket's limits are folded into its Redis key (see RateLimiterService) rather
        // than reconciled in place, so a config change addresses a fresh bucket immediately —
        // proving the raised limit took effect requires up to 5 requests to succeed here,
        // rather than staying stuck denied forever on the old, already-exhausted capacity=1
        // bucket.
        val results = (1..5).map { rateLimiterService.tryConsume(tenantId) }
        assertEquals(5, results.count { it == RateLimitResult.Allowed })
    }

    @Test
    fun `bucket refills after the burst window elapses`() {
        val tenantId = TenantId.generate()
        tenantConfigRepository.upsert(cloudConfig(tenantId, rateLimitPerSecond = 1))

        assertEquals(RateLimitResult.Allowed, rateLimiterService.tryConsume(tenantId))
        assertTrue(rateLimiterService.tryConsume(tenantId) is RateLimitResult.Denied)

        Thread.sleep(1100)

        assertEquals(RateLimitResult.Allowed, rateLimiterService.tryConsume(tenantId))
    }
}
