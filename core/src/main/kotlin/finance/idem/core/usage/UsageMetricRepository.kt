package finance.idem.core.usage

import finance.idem.core.TenantId
import java.time.Instant

interface UsageMetricRepository {
    /** Records one raw usage event. Called on the write path — keep this cheap. */
    fun recordEvent(
        tenantId: TenantId,
        metricType: MetricType,
        amount: Long,
        occurredAt: Instant,
    )

    /** Hourly rollup buckets for this tenant/metric in [from, to). */
    fun findHourlyBuckets(
        tenantId: TenantId,
        metricType: MetricType,
        from: Instant,
        to: Instant,
    ): List<UsageMetric>

    /** Sum of raw (not-yet-rolled-up) events for this tenant/metric at or after [since]. */
    fun sumRawSince(
        tenantId: TenantId,
        metricType: MetricType,
        since: Instant,
    ): Long

    /**
     * Aggregates raw events in [hourStart, hourEnd) into hourly buckets, across all tenants.
     * Idempotent — re-running for an already-rolled-up hour is a no-op. Returns the number of
     * tenant/metric buckets written. Rollup-job use only — requires cross-tenant visibility.
     */
    fun rollupHour(
        hourStart: Instant,
        hourEnd: Instant,
    ): Int

    /** The exclusive upper bound of hours already rolled up. */
    fun currentWatermark(): Instant

    fun advanceWatermark(newWatermark: Instant)
}
