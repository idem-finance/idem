package finance.idem.core.tenant

import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertTrue

class RateLimitPolicyTest {
    private val tenantId = TenantId.generate()

    @Test
    fun `OPEN_SOURCE is always unlimited, ignoring configured fields`() {
        val config = tenantConfig(plan = TenantPlan.OPEN_SOURCE, rateLimitPerSecond = 5, rateLimitPerMinute = 50)

        val decision = RateLimitPolicy.resolve(config, cloudDefaultPerSecond = 100, cloudDefaultPerMinute = 1000)

        assertTrue(decision.isUnlimited)
    }

    @Test
    fun `CLOUD with both fields null falls back to both cloud defaults`() {
        val config = tenantConfig(plan = TenantPlan.CLOUD, rateLimitPerSecond = null, rateLimitPerMinute = null)

        val decision = RateLimitPolicy.resolve(config, cloudDefaultPerSecond = 100, cloudDefaultPerMinute = 1000)

        assertTrue(decision == RateLimitDecision(100, 1000))
    }

    @Test
    fun `CLOUD with perSecond set and perMinute null mixes the override with the default`() {
        val config = tenantConfig(plan = TenantPlan.CLOUD, rateLimitPerSecond = 5, rateLimitPerMinute = null)

        val decision = RateLimitPolicy.resolve(config, cloudDefaultPerSecond = 100, cloudDefaultPerMinute = 1000)

        assertTrue(decision == RateLimitDecision(5, 1000))
    }

    @Test
    fun `CLOUD with perSecond null and perMinute set mixes the default with the override`() {
        val config = tenantConfig(plan = TenantPlan.CLOUD, rateLimitPerSecond = null, rateLimitPerMinute = 50)

        val decision = RateLimitPolicy.resolve(config, cloudDefaultPerSecond = 100, cloudDefaultPerMinute = 1000)

        assertTrue(decision == RateLimitDecision(100, 50))
    }

    @Test
    fun `CLOUD with both fields set honors both overrides`() {
        val config = tenantConfig(plan = TenantPlan.CLOUD, rateLimitPerSecond = 5, rateLimitPerMinute = 50)

        val decision = RateLimitPolicy.resolve(config, cloudDefaultPerSecond = 100, cloudDefaultPerMinute = 1000)

        assertTrue(decision == RateLimitDecision(5, 50))
    }

    @Test
    fun `ENTERPRISE with both fields null is unlimited`() {
        val config = tenantConfig(plan = TenantPlan.ENTERPRISE, rateLimitPerSecond = null, rateLimitPerMinute = null)

        val decision = RateLimitPolicy.resolve(config, cloudDefaultPerSecond = 100, cloudDefaultPerMinute = 1000)

        assertTrue(decision.isUnlimited)
    }

    @Test
    fun `ENTERPRISE with only perSecond set honors it and leaves perMinute unlimited — no fallback to CLOUD defaults`() {
        val config = tenantConfig(plan = TenantPlan.ENTERPRISE, rateLimitPerSecond = 5, rateLimitPerMinute = null)

        val decision = RateLimitPolicy.resolve(config, cloudDefaultPerSecond = 100, cloudDefaultPerMinute = 1000)

        assertTrue(decision == RateLimitDecision(5, null))
    }

    @Test
    fun `ENTERPRISE with both fields set honors both explicit values`() {
        val config = tenantConfig(plan = TenantPlan.ENTERPRISE, rateLimitPerSecond = 5, rateLimitPerMinute = 50)

        val decision = RateLimitPolicy.resolve(config, cloudDefaultPerSecond = 100, cloudDefaultPerMinute = 1000)

        assertTrue(decision == RateLimitDecision(5, 50))
    }

    private fun tenantConfig(
        plan: TenantPlan,
        rateLimitPerSecond: Int?,
        rateLimitPerMinute: Int?,
    ) = TenantConfig(
        tenantId = tenantId,
        plan = plan,
        rateLimitPerSecond = rateLimitPerSecond,
        rateLimitPerMinute = rateLimitPerMinute,
        featureFlags = emptySet(),
        hmacKey = null,
        billingCustomerId = null,
        createdAt = Instant.now(),
        suspendedAt = null,
    )
}
