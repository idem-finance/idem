package finance.idem.infrastructure.persistence.events

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "domain_events")
class DomainEventDataModel(
    @Id
    val id: UUID,
    @Column(name = "tenant_id", nullable = false)
    val tenantId: UUID,
    @Column(name = "event_type", nullable = false)
    val eventType: String,
    @Column(name = "reference_id", nullable = false)
    val referenceId: UUID,
    @Column(name = "reference_type", nullable = false)
    val referenceType: String,
    @Column(name = "correlation_id", nullable = false)
    val correlationId: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    val payload: String,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    protected constructor() : this(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "",
        UUID.randomUUID(),
        "",
        "",
        "{}",
        Instant.now(),
        Instant.now(),
    )
}
