package finance.idem.core.usage

import finance.idem.core.TenantId
import java.time.Instant

interface UsageMetricRepository {
    /**
     * Records one raw usage event. Called on the write path — keep this cheap.
     *
     * [idempotencyKey], when non-null, dedups against `(tenantId, metricType, idempotencyKey)` —
     * a second call with the same triple is a no-op. Used by callers whose own event source can
     * redeliver/retry (chain readers and webhook receivers, keyed off the same idempotency key
     * already used for the ledger post) so a redelivery doesn't double-count usage even though
     * the ledger-side post is itself a safe no-op on retry.
     */
    fun recordEvent(
        tenantId: TenantId,
        metricType: MetricType,
        amount: Long,
        occurredAt: Instant,
        idempotencyKey: String? = null,
    )

    /** Hourly-rollup sums per metric type for this tenant in [from, to), one query. */
    fun hourlyBucketSums(
        tenantId: TenantId,
        from: Instant,
        to: Instant,
    ): Map<MetricType, Long>

    /** Sum of raw (not-yet-rolled-up) events per metric type for this tenant in [from, to), one query. */
    fun rawSumsBetween(
        tenantId: TenantId,
        from: Instant,
        to: Instant,
    ): Map<MetricType, Long>

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
