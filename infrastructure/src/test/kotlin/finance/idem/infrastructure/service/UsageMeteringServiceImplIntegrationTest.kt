package finance.idem.infrastructure.service

import finance.idem.core.TenantId
import finance.idem.core.tenant.TenantConfig
import finance.idem.core.tenant.TenantConfigRepository
import finance.idem.core.tenant.TenantPlan
import finance.idem.core.usage.MetricType
import finance.idem.infrastructure.SharedPostgresTestBase
import finance.idem.infrastructure.persistence.usage.UsageMetricRepositoryAdapter
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Exercises [UsageMeteringServiceImpl] against a real Postgres instance rather than a mocked
 * [finance.idem.core.usage.UsageMetricRepository] (see [UsageMeteringServiceImplTest] for the
 * mock-based unit tests) -- idem#275 asks for real-DB proof that hourly rollup aggregation and
 * monthly usage totals are correct, not just that the service calls the right repository
 * methods. Simulates what [UsageMetricsRollupJob] does on a schedule by calling
 * [UsageMetricRepositoryAdapter.rollupHour] directly for a past hour, then records additional
 * raw events "now" (not yet rolled up), and asserts the monthly total correctly sums both.
 *
 * [UsageMetricRepositoryAdapter.advanceWatermark] mutates a single global, non-tenant-scoped
 * row (`usage_metrics_rollup_state`, id fixed to 1 by design -- rollup is cross-tenant, so
 * there is no per-tenant row to scope it to). Isolation between this class's watermark writes
 * and any other test currently depends entirely on `@DataJpaTest`'s per-method transaction
 * rollback -- adding `@Commit` here, or running this suite against a shared non-rolled-back
 * schema, would let one test's watermark advance shift the rollup/raw split point for every
 * other in-flight `getMonthlyUsage` call.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UsageMeteringServiceImpl::class, UsageMetricRepositoryAdapter::class)
class UsageMeteringServiceImplIntegrationTest : SharedPostgresTestBase() {
    @Autowired lateinit var service: UsageMeteringServiceImpl

    @Autowired lateinit var adapter: UsageMetricRepositoryAdapter

    @MockitoBean
    lateinit var tenantConfigRepository: TenantConfigRepository

    private val tenantId = TenantId.generate()

    private fun rollUpPastHour(amount: Long) {
        // Clamped to the current UTC month's start so a run within ~3h of UTC midnight on the
        // 1st can't compute an hourStart in the *previous* month: advanceWatermark(hourEnd)
        // below must never trail monthStart, or getMonthlyUsage's
        // splitPoint = maxOf(watermark, monthStart) collapses back to monthStart and silently
        // drops this rolled-up event from the sum.
        val monthStart =
            YearMonth
                .now(ZoneOffset.UTC)
                .atDay(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
        val hourStart = maxOf(monthStart, Instant.now().minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.HOURS))
        val hourEnd = hourStart.plus(Duration.ofHours(1))
        adapter.recordEvent(tenantId, MetricType.TRANSACTION_COUNT, amount, hourStart.plusSeconds(60))
        adapter.rollupHour(hourStart, hourEnd)
        // Global, non-tenant-scoped watermark write -- see class doc comment above.
        adapter.advanceWatermark(hourEnd)
    }

    @Test
    fun `getMonthlyUsage sums a rolled-up hour plus a subsequent raw event`() {
        whenever(tenantConfigRepository.findByTenantId(tenantId)).thenReturn(null)

        rollUpPastHour(amount = 3L)
        adapter.recordEvent(tenantId, MetricType.TRANSACTION_COUNT, 2L, Instant.now())

        val summary = service.getMonthlyUsage(tenantId, YearMonth.now())

        assertEquals(5L, summary.usage[MetricType.TRANSACTION_COUNT], "3 rolled-up + 2 raw must both be counted, exactly once each")
    }

    @Test
    fun `getMonthlyUsage does not double-count a rolled-up hour on repeated calls`() {
        whenever(tenantConfigRepository.findByTenantId(tenantId)).thenReturn(null)

        rollUpPastHour(amount = 4L)

        val first = service.getMonthlyUsage(tenantId, YearMonth.now())
        val second = service.getMonthlyUsage(tenantId, YearMonth.now())

        assertEquals(4L, first.usage[MetricType.TRANSACTION_COUNT])
        assertEquals(4L, second.usage[MetricType.TRANSACTION_COUNT], "re-querying the same period must be stable, not accumulate")
    }

    @Test
    fun `getMonthlyUsage is scoped to the requested tenant, never mixing another tenant's events`() {
        val otherTenant = TenantId.generate()
        whenever(tenantConfigRepository.findByTenantId(tenantId)).thenReturn(null)
        whenever(tenantConfigRepository.findByTenantId(otherTenant)).thenReturn(null)

        adapter.recordEvent(tenantId, MetricType.TRANSACTION_COUNT, 1L, Instant.now())
        adapter.recordEvent(otherTenant, MetricType.TRANSACTION_COUNT, 100L, Instant.now())

        val summary = service.getMonthlyUsage(tenantId, YearMonth.now())

        assertEquals(1L, summary.usage[MetricType.TRANSACTION_COUNT], "tenant A's usage must never include tenant B's recorded events")
    }

    @Test
    fun `getMonthlyUsage applies the tenant's configured monthly limit`() {
        val config =
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
                monthlyTransactionLimit = 250L,
            )
        whenever(tenantConfigRepository.findByTenantId(tenantId)).thenReturn(config)

        val summary = service.getMonthlyUsage(tenantId, YearMonth.now())

        assertEquals(250L, summary.limits[MetricType.TRANSACTION_COUNT])
    }

    @Test
    fun `getMonthlyUsage treats an unconfigured tenant as unlimited`() {
        whenever(tenantConfigRepository.findByTenantId(tenantId)).thenReturn(null)

        val summary = service.getMonthlyUsage(tenantId, YearMonth.now())

        assertNull(
            summary.limits[MetricType.TRANSACTION_COUNT],
            "no persisted TenantConfig row must fall back to TenantConfig.default (unlimited)",
        )
    }
}
