package finance.idem.core.usage

import finance.idem.core.TenantId
import java.time.Instant

/** Current-period usage per metric, alongside the tenant's configured limit (`null` = unlimited). */
data class UsageSummary(
    val tenantId: TenantId,
    val periodStart: Instant,
    val periodEnd: Instant,
    val usage: Map<MetricType, Long>,
    val limits: Map<MetricType, Long?>,
)
