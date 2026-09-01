package finance.idem.infrastructure.persistence.usage

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** Single-row watermark tracking how far the hourly rollup job has advanced. No tenant data, no RLS. */
@Entity
@Table(name = "usage_metrics_rollup_state")
class UsageMetricRollupStateDataModel(
    @Id
    @Column(name = "id")
    val id: Short,
    @Column(name = "last_rolled_up_hour", nullable = false)
    val lastRolledUpHour: Instant,
) {
    protected constructor() : this(1, Instant.EPOCH)
}
