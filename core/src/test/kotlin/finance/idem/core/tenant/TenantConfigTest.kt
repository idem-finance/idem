package finance.idem.core.tenant

import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TenantConfigTest {
    private val tenantId = TenantId.generate()

    @Test
    fun `default returns OPEN_SOURCE plan with no limits, flags, key, or suspension`() {
        val config = TenantConfig.default(tenantId)

        assertTrue(config.tenantId == tenantId)
        assertTrue(config.plan == TenantPlan.OPEN_SOURCE)
        assertNull(config.rateLimitPerSecond)
        assertNull(config.rateLimitPerMinute)
        assertTrue(config.featureFlags.isEmpty())
        assertNull(config.hmacKey)
        assertNull(config.billingCustomerId)
        assertFalse(config.isSuspended)
    }

    @Test
    fun `hasFeature returns true only for flags present in the set`() {
        val config = tenantConfig(featureFlags = setOf("compliance_export"))

        assertTrue(config.hasFeature("compliance_export"))
        assertFalse(config.hasFeature("other_flag"))
    }

    @Test
    fun `isSuspended is false when suspendedAt is null`() {
        assertFalse(tenantConfig(suspendedAt = null).isSuspended)
    }

    @Test
    fun `isSuspended is true when suspendedAt is set`() {
        assertTrue(tenantConfig(suspendedAt = Instant.now()).isSuspended)
    }

    private fun tenantConfig(
        featureFlags: Set<String> = emptySet(),
        suspendedAt: Instant? = null,
    ) = TenantConfig(
        tenantId = tenantId,
        plan = TenantPlan.CLOUD,
        rateLimitPerSecond = 10,
        rateLimitPerMinute = 100,
        featureFlags = featureFlags,
        hmacKey = "some-key",
        billingCustomerId = "cus_123",
        createdAt = Instant.now(),
        suspendedAt = suspendedAt,
    )
}
