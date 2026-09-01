package finance.idem.core.usage

import finance.idem.core.TenantId
import java.time.Instant

/** One hourly rollup bucket — the sum of a metric's raw events within [periodStart, periodEnd). */
data class UsageMetric(
    val tenantId: TenantId,
    val metricType: MetricType,
    val value: Long,
    val periodStart: Instant,
    val periodEnd: Instant,
)
