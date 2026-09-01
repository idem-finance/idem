package finance.idem.core.usage

import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class UsageMetricTest {
    private val tenantId = TenantId.generate()

    @Test
    fun `properties expose their constructor values`() {
        val periodStart = Instant.parse("2026-08-01T00:00:00Z")
        val periodEnd = Instant.parse("2026-08-01T01:00:00Z")
        val metric = UsageMetric(tenantId, MetricType.CHAIN_EVENT_COUNT, 3L, periodStart, periodEnd)

        assertEquals(tenantId, metric.tenantId)
        assertEquals(MetricType.CHAIN_EVENT_COUNT, metric.metricType)
        assertEquals(3L, metric.value)
        assertEquals(periodStart, metric.periodStart)
        assertEquals(periodEnd, metric.periodEnd)
    }

    @Test
    fun `equality is based on all fields`() {
        val periodStart = Instant.parse("2026-08-01T00:00:00Z")
        val periodEnd = Instant.parse("2026-08-01T01:00:00Z")
        val a = UsageMetric(tenantId, MetricType.TRANSACTION_COUNT, 5L, periodStart, periodEnd)
        val b = UsageMetric(tenantId, MetricType.TRANSACTION_COUNT, 5L, periodStart, periodEnd)
        assertEquals(a, b)
    }

    @Test
    fun `different metricTypes are not equal`() {
        val periodStart = Instant.parse("2026-08-01T00:00:00Z")
        val periodEnd = Instant.parse("2026-08-01T01:00:00Z")
        assertNotEquals(
            UsageMetric(tenantId, MetricType.TRANSACTION_COUNT, 5L, periodStart, periodEnd),
            UsageMetric(tenantId, MetricType.API_CALL_COUNT, 5L, periodStart, periodEnd),
        )
    }

    @Test
    fun `copy with updated value reflects new amount`() {
        val original = UsageMetric(tenantId, MetricType.ENTRY_COUNT, 2L, Instant.EPOCH, Instant.EPOCH)
        val updated = original.copy(value = 9L)
        assertEquals(9L, updated.value)
        assertEquals(MetricType.ENTRY_COUNT, updated.metricType)
    }

    @Test
    fun `toString and hashCode do not throw and are consistent with equality`() {
        val a = UsageMetric(tenantId, MetricType.ENTRY_COUNT, 2L, Instant.EPOCH, Instant.EPOCH)
        val b = UsageMetric(tenantId, MetricType.ENTRY_COUNT, 2L, Instant.EPOCH, Instant.EPOCH)
        assertEquals(a.toString(), b.toString())
        assertEquals(a.hashCode(), b.hashCode())
    }
}
