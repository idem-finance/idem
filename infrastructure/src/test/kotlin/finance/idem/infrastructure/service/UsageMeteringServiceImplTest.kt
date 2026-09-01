package finance.idem.infrastructure.service

import finance.idem.core.TenantId
import finance.idem.core.tenant.TenantConfig
import finance.idem.core.tenant.TenantConfigRepository
import finance.idem.core.tenant.TenantPlan
import finance.idem.core.usage.MetricType
import finance.idem.core.usage.UsageMetricRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

@ExtendWith(MockitoExtension::class)
class UsageMeteringServiceImplTest {
    @Mock lateinit var usageMetricRepository: UsageMetricRepository

    @Mock lateinit var tenantConfigRepository: TenantConfigRepository

    private lateinit var service: UsageMeteringServiceImpl

    private val tenantId = TenantId.generate()
    private val yearMonth = YearMonth.of(2026, 8)
    private val monthStart = yearMonth.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()
    private val monthEnd =
        yearMonth
            .plusMonths(1)
            .atDay(1)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()

    @BeforeEach
    fun setUp() {
        service = UsageMeteringServiceImpl(usageMetricRepository, tenantConfigRepository)
    }

    // tenantId is matched with any() rather than eq() below: Mockito's eq()/any() box a
    // @JvmInline value class (TenantId) into a real TenantId object, while the real call site
    // passes the compiler-erased UUID directly — eq(tenantId) then always reports a mismatch.
    // tenantId-scoped routing is already covered by the getMonthlyUsage tests below, which
    // stub via direct (non-matcher) invocation and so don't hit this erasure asymmetry.

    @Test
    fun `recordUsage delegates to the repository with the given amount and a current timestamp`() {
        service.recordUsage(tenantId, MetricType.TRANSACTION_COUNT, 3L)

        verify(usageMetricRepository).recordEvent(any(), eq(MetricType.TRANSACTION_COUNT), eq(3L), any(), eq(null))
    }

    @Test
    fun `recordUsage defaults amount to 1`() {
        service.recordUsage(tenantId, MetricType.API_CALL_COUNT)

        verify(usageMetricRepository).recordEvent(any(), eq(MetricType.API_CALL_COUNT), eq(1L), any(), eq(null))
    }

    @Test
    fun `recordUsage passes the idempotencyKey through to the repository`() {
        service.recordUsage(tenantId, MetricType.CHAIN_EVENT_COUNT, 1L, idempotencyKey = "EVM_1:0xabc:0")

        verify(usageMetricRepository).recordEvent(any(), eq(MetricType.CHAIN_EVENT_COUNT), eq(1L), any(), eq("EVM_1:0xabc:0"))
    }

    @Test
    fun `getMonthlyUsage for a past, fully rolled-up month sums only hourly buckets, no raw top-up`() {
        val watermark = monthEnd.plus(java.time.Duration.ofDays(5)) // watermark well past this month
        whenever(usageMetricRepository.currentWatermark()).thenReturn(watermark)
        whenever(
            usageMetricRepository.hourlyBucketSums(tenantId, monthStart, monthEnd),
        ).thenReturn(mapOf(MetricType.TRANSACTION_COUNT to 15L))
        whenever(tenantConfigRepository.findByTenantId(tenantId)).thenReturn(null)

        val summary = service.getMonthlyUsage(tenantId, yearMonth)

        assertEquals(15L, summary.usage[MetricType.TRANSACTION_COUNT])
        verify(usageMetricRepository, org.mockito.kotlin.never()).rawSumsBetween(any(), any(), any())
    }

    @Test
    fun `getMonthlyUsage for the current month tops up with raw events since the watermark, bounded to monthEnd`() {
        val watermark = monthStart.plusSeconds(7200) // 2 hours into the month
        whenever(usageMetricRepository.currentWatermark()).thenReturn(watermark)
        whenever(
            usageMetricRepository.hourlyBucketSums(tenantId, monthStart, watermark),
        ).thenReturn(mapOf(MetricType.TRANSACTION_COUNT to 20L))
        whenever(usageMetricRepository.rawSumsBetween(tenantId, watermark, monthEnd)).thenReturn(mapOf(MetricType.TRANSACTION_COUNT to 4L))
        whenever(tenantConfigRepository.findByTenantId(tenantId)).thenReturn(null)

        val summary = service.getMonthlyUsage(tenantId, yearMonth)

        assertEquals(24L, summary.usage[MetricType.TRANSACTION_COUNT])
        // The crux of the fix: rawSumsBetween's upper bound is monthEnd, not "no bound" — a
        // lagging watermark can no longer leak a later period's raw events into this month.
        // tenantId is matched via a plain (non-eq) value -- see the class-level comment on why
        // eq() on this @JvmInline value class always reports a false mismatch here.
        verify(usageMetricRepository).rawSumsBetween(tenantId, watermark, monthEnd)
    }

    @Test
    fun `getMonthlyUsage for a past month with a lagging watermark still bounds the raw query to monthEnd`() {
        // Watermark stalled well before this month even started (e.g. the rollup job was
        // broken) — splitPoint clamps to monthStart, so the ENTIRE month is raw territory,
        // and rawSumsBetween must be called with monthEnd as its upper bound, not left open.
        val watermark = monthStart.minus(java.time.Duration.ofDays(60))
        whenever(usageMetricRepository.currentWatermark()).thenReturn(watermark)
        whenever(usageMetricRepository.hourlyBucketSums(tenantId, monthStart, monthStart)).thenReturn(emptyMap())
        whenever(usageMetricRepository.rawSumsBetween(tenantId, monthStart, monthEnd)).thenReturn(mapOf(MetricType.TRANSACTION_COUNT to 7L))
        whenever(tenantConfigRepository.findByTenantId(tenantId)).thenReturn(null)

        val summary = service.getMonthlyUsage(tenantId, yearMonth)

        assertEquals(7L, summary.usage[MetricType.TRANSACTION_COUNT])
        // tenantId matched via a plain (non-eq) value -- see the class-level comment above.
        verify(usageMetricRepository).rawSumsBetween(tenantId, monthStart, monthEnd)
    }

    @Test
    fun `getMonthlyUsage uses TenantConfig limitFor when a config row exists`() {
        whenever(usageMetricRepository.currentWatermark()).thenReturn(Instant.EPOCH)
        whenever(usageMetricRepository.hourlyBucketSums(any(), any(), any())).thenReturn(emptyMap())
        whenever(usageMetricRepository.rawSumsBetween(any(), any(), any())).thenReturn(emptyMap())
        whenever(tenantConfigRepository.findByTenantId(tenantId)).thenReturn(
            TenantConfig(
                tenantId = tenantId,
                plan = TenantPlan.CLOUD,
                rateLimitPerSecond = null,
                rateLimitPerMinute = null,
                featureFlags = emptySet(),
                hmacKey = null,
                billingCustomerId = null,
                createdAt = Instant.now(),
                suspendedAt = null,
                monthlyTransactionLimit = 1000L,
            ),
        )

        val summary = service.getMonthlyUsage(tenantId, yearMonth)

        assertEquals(1000L, summary.limits[MetricType.TRANSACTION_COUNT])
    }

    @Test
    fun `getMonthlyUsage falls back to TenantConfig default (unlimited) when no config row exists`() {
        whenever(usageMetricRepository.currentWatermark()).thenReturn(Instant.EPOCH)
        whenever(usageMetricRepository.hourlyBucketSums(any(), any(), any())).thenReturn(emptyMap())
        whenever(usageMetricRepository.rawSumsBetween(any(), any(), any())).thenReturn(emptyMap())
        whenever(tenantConfigRepository.findByTenantId(tenantId)).thenReturn(null)

        val summary = service.getMonthlyUsage(tenantId, yearMonth)

        MetricType.entries.forEach { assertNull(summary.limits[it]) }
    }
}
