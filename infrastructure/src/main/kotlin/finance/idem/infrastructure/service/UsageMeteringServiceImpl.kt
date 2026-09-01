package finance.idem.infrastructure.service

import finance.idem.application.usage.UsageMeteringService
import finance.idem.core.TenantId
import finance.idem.core.tenant.TenantConfig
import finance.idem.core.tenant.TenantConfigRepository
import finance.idem.core.usage.MetricType
import finance.idem.core.usage.UsageMetricRepository
import finance.idem.core.usage.UsageSummary
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

@Service
class UsageMeteringServiceImpl(
    private val usageMetricRepository: UsageMetricRepository,
    private val tenantConfigRepository: TenantConfigRepository,
) : UsageMeteringService {
    /**
     * Default propagation (REQUIRED): joins the caller's ambient transaction when one is
     * active, so a metering failure inside an existing @Transactional (e.g.
     * PostTransactionService.execute()) rolls back that transaction along with it — usage
     * metering is a side effect of the primary operation, not an isolated concern, per this
     * codebase's single-transaction side-effect rule (see CLAUDE.md). Callers with no ambient
     * transaction (background/@Scheduled jobs) simply start a fresh transaction, same as before.
     */
    @Transactional
    override fun recordUsage(
        tenantId: TenantId,
        metricType: MetricType,
        amount: Long,
        idempotencyKey: String?,
    ) {
        usageMetricRepository.recordEvent(tenantId, metricType, amount, Instant.now(), idempotencyKey)
    }

    override fun getMonthlyUsage(
        tenantId: TenantId,
        yearMonth: YearMonth,
    ): UsageSummary {
        val monthStart = yearMonth.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val monthEnd =
            yearMonth
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()

        // Rollup covers [monthStart, splitPoint); raw top-up covers [splitPoint, monthEnd) —
        // no overlap, no gap. Clamped to monthEnd so a past month never picks up a stale
        // watermark's raw tail from a later period; rawSumsBetween is bounded to monthEnd,
        // and skipped entirely once splitPoint reaches it, so a lagging watermark can't leak
        // a later period's raw events into this month's total.
        val watermark = usageMetricRepository.currentWatermark()
        val splitPoint = maxOf(watermark, monthStart).coerceAtMost(monthEnd)

        val config = tenantConfigRepository.findByTenantId(tenantId) ?: TenantConfig.default(tenantId)

        val hourlySums = usageMetricRepository.hourlyBucketSums(tenantId, monthStart, splitPoint)
        val rawSums =
            if (splitPoint < monthEnd) {
                usageMetricRepository.rawSumsBetween(tenantId, splitPoint, monthEnd)
            } else {
                emptyMap()
            }
        val usage = MetricType.entries.associateWith { (hourlySums[it] ?: 0L) + (rawSums[it] ?: 0L) }
        val limits = MetricType.entries.associateWith { config.limitFor(it) }

        return UsageSummary(
            tenantId = tenantId,
            periodStart = monthStart,
            periodEnd = monthEnd,
            usage = usage,
            limits = limits,
        )
    }
}
