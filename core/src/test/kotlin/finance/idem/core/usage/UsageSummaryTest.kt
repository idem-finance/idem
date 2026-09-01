package finance.idem.core.usage

import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class UsageSummaryTest {
    private val tenantId = TenantId.generate()

    private fun summary(
        usage: Map<MetricType, Long> = mapOf(MetricType.TRANSACTION_COUNT to 10L),
        limits: Map<MetricType, Long?> = mapOf(MetricType.TRANSACTION_COUNT to null),
    ) = UsageSummary(
        tenantId = tenantId,
        periodStart = Instant.parse("2026-08-01T00:00:00Z"),
        periodEnd = Instant.parse("2026-09-01T00:00:00Z"),
        usage = usage,
        limits = limits,
    )

    @Test
    fun `equality is based on all fields`() {
        assertEquals(summary(), summary())
    }

    @Test
    fun `different usage maps are not equal`() {
        assertNotEquals(
            summary(usage = mapOf(MetricType.TRANSACTION_COUNT to 10L)),
            summary(usage = mapOf(MetricType.TRANSACTION_COUNT to 20L)),
        )
    }

    @Test
    fun `a null limit means unlimited`() {
        assertNull(summary().limits[MetricType.TRANSACTION_COUNT])
    }

    @Test
    fun `copy with updated usage reflects new value`() {
        val original = summary()
        val updated = original.copy(usage = mapOf(MetricType.API_CALL_COUNT to 3L))
        assertEquals(3L, updated.usage[MetricType.API_CALL_COUNT])
        assertEquals(original.tenantId, updated.tenantId)
    }
}
