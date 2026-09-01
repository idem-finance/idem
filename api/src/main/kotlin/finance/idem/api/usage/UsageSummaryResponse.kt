package finance.idem.api.usage

import finance.idem.core.usage.MetricType
import finance.idem.core.usage.UsageSummary
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class UsageSummaryResponse(
    @Schema(description = "Inclusive lower bound of the current billing period")
    val periodStart: Instant,
    @Schema(description = "Exclusive upper bound of the current billing period")
    val periodEnd: Instant,
    @Schema(description = "Usage and configured limit per metric type")
    val metrics: List<UsageMetricResponse>,
) {
    companion object {
        fun from(summary: UsageSummary) =
            UsageSummaryResponse(
                periodStart = summary.periodStart,
                periodEnd = summary.periodEnd,
                metrics = MetricType.entries.map { UsageMetricResponse.from(it, summary) },
            )
    }
}
