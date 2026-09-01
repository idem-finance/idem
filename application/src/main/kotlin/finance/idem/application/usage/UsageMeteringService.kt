package finance.idem.application.usage

import finance.idem.core.TenantId
import finance.idem.core.usage.MetricType
import finance.idem.core.usage.UsageSummary
import java.time.YearMonth

interface UsageMeteringService {
    /**
     * Records a usage event for billing/self-serve visibility. Must never throw in a way that
     * can fail a caller's own transaction — implementations record failures and move on.
     */
    fun recordUsage(
        tenantId: TenantId,
        metricType: MetricType,
        amount: Long = 1,
    )

    fun getMonthlyUsage(
        tenantId: TenantId,
        yearMonth: YearMonth,
    ): UsageSummary
}
