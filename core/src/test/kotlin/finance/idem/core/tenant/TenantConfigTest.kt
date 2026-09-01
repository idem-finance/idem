package finance.idem.core.tenant

import finance.idem.core.TenantId
import finance.idem.core.usage.MetricType
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
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
        MetricType.entries.forEach { assertNull(config.limitFor(it)) }
    }

    @Test
    fun `limitFor returns the field matching each metric type`() {
        val config =
            tenantConfig().copy(
                monthlyTransactionLimit = 1L,
                monthlyApiCallLimit = 2L,
                monthlyChainEventLimit = 3L,
                monthlyWebhookDeliveryLimit = 4L,
                monthlyEntryLimit = 5L,
            )

        assertEquals(1L, config.limitFor(MetricType.TRANSACTION_COUNT))
        assertEquals(2L, config.limitFor(MetricType.API_CALL_COUNT))
        assertEquals(3L, config.limitFor(MetricType.CHAIN_EVENT_COUNT))
        assertEquals(4L, config.limitFor(MetricType.WEBHOOK_DELIVERY_COUNT))
        assertEquals(5L, config.limitFor(MetricType.ENTRY_COUNT))
    }

    @Test
    fun `limitFor returns null for an unconfigured metric type`() {
        assertNull(tenantConfig().limitFor(MetricType.TRANSACTION_COUNT))
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
