package finance.idem.infrastructure.persistence.usage

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Hourly rollup bucket. Rows are written by the rollup job's native aggregate INSERT, not JPA save. */
@Entity
@Table(name = "usage_metrics_hourly")
class UsageMetricHourlyDataModel(
    @Id
    @Column(name = "id")
    val id: UUID,
    @Column(name = "tenant_id", nullable = false)
    val tenantId: UUID,
    @Column(name = "metric_type", nullable = false)
    val metricType: String,
    @Column(name = "value", nullable = false)
    val value: Long,
    @Column(name = "period_start", nullable = false)
    val periodStart: Instant,
    @Column(name = "period_end", nullable = false)
    val periodEnd: Instant,
) {
    protected constructor() : this(UUID.randomUUID(), UUID.randomUUID(), "", 0L, Instant.now(), Instant.now())
}
