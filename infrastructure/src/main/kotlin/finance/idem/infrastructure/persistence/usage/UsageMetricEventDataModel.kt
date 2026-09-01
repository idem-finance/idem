package finance.idem.infrastructure.persistence.usage

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Raw, append-only usage event. High write volume — BIGSERIAL PK, no app-assigned UUID. */
@Entity
@Table(name = "usage_metrics")
class UsageMetricEventDataModel(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long,
    @Column(name = "tenant_id", nullable = false)
    val tenantId: UUID,
    @Column(name = "metric_type", nullable = false)
    val metricType: String,
    @Column(name = "amount", nullable = false)
    val amount: Long,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
) {
    protected constructor() : this(0L, UUID.randomUUID(), "", 0L, Instant.now())

    companion object {
        fun new(
            tenantId: UUID,
            metricType: String,
            amount: Long,
            occurredAt: Instant,
        ) = UsageMetricEventDataModel(id = 0L, tenantId = tenantId, metricType = metricType, amount = amount, occurredAt = occurredAt)
    }
}
