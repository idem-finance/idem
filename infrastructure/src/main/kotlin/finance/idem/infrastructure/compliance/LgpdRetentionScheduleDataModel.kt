package finance.idem.infrastructure.compliance

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "lgpd_retention_schedule")
class LgpdRetentionScheduleDataModel(
    @Id
    val id: UUID,

    @Column(name = "tenant_id", nullable = false)
    val tenantId: UUID,

    @Column(name = "entity_type", nullable = false)
    val entityType: String,

    @Column(name = "entity_id", nullable = false)
    val entityId: String,

    @Column(name = "retention_years", nullable = false)
    val retentionYears: Int,

    @Column(name = "scheduled_at", nullable = false)
    val scheduledAt: Instant,

    @Column(name = "deletion_due_at", nullable = false)
    val deletionDueAt: Instant,

    @Column(name = "processed_at")
    var processedAt: Instant? = null,
) {
    protected constructor() : this(
        UUID.randomUUID(), UUID.randomUUID(), "", "", 7,
        Instant.now(), Instant.now(), null,
    )
}
