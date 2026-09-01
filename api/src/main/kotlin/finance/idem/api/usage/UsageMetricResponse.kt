package finance.idem.api.usage

import finance.idem.core.usage.MetricType
import finance.idem.core.usage.UsageSummary
import io.swagger.v3.oas.annotations.media.Schema

data class UsageMetricResponse(
    @Schema(description = "Metric type")
    val metricType: MetricType,
    @Schema(description = "Usage recorded so far in the current period")
    val usage: Long,
    @Schema(description = "Configured monthly limit, or null if unlimited/not configured")
    val limit: Long?,
) {
    companion object {
        fun from(
            metricType: MetricType,
            summary: UsageSummary,
        ) = UsageMetricResponse(
            metricType = metricType,
            usage = summary.usage[metricType] ?: 0L,
            limit = summary.limits[metricType],
        )
    }
}
