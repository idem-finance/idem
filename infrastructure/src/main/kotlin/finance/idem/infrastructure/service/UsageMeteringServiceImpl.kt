package finance.idem.infrastructure.service

import finance.idem.application.usage.UsageMeteringService
import finance.idem.core.TenantId
import finance.idem.core.tenant.TenantConfig
import finance.idem.core.tenant.TenantConfigRepository
import finance.idem.core.usage.MetricType
import finance.idem.core.usage.UsageMetricRepository
import finance.idem.core.usage.UsageSummary
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
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
     * REQUIRES_NEW so a metering failure runs and fails in its own transaction, fully isolated
     * from whatever transaction the caller (e.g. PostTransactionService) is in — a metering
     * write can never roll back the ledger commit that triggered it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun recordUsage(
        tenantId: TenantId,
        metricType: MetricType,
        amount: Long,
    ) {
        usageMetricRepository.recordEvent(tenantId, metricType, amount, Instant.now())
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
        // watermark's raw tail from a later period; rawSum is skipped once splitPoint reaches
        // monthEnd, since sumRawSince has no upper bound of its own.
        val watermark = usageMetricRepository.currentWatermark()
        val splitPoint = maxOf(watermark, monthStart).coerceAtMost(monthEnd)

        val config = tenantConfigRepository.findByTenantId(tenantId) ?: TenantConfig.default(tenantId)

        val usage =
            MetricType.entries.associateWith { metricType ->
                val hourlySum = usageMetricRepository.findHourlyBuckets(tenantId, metricType, monthStart, splitPoint).sumOf { it.value }
                val rawSum = if (splitPoint < monthEnd) usageMetricRepository.sumRawSince(tenantId, metricType, splitPoint) else 0L
                hourlySum + rawSum
            }
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
