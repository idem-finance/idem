package finance.idem.infrastructure.persistence.outbox

import finance.idem.application.outbox.OutboxStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "webhook_outbox")
class WebhookOutboxDataModel(
    @Id
    val id: UUID,
    @Column(name = "tenant_id", nullable = false)
    val tenantId: UUID,
    @Column(name = "transaction_id", nullable = false)
    val transactionId: UUID,
    @Column(name = "event_type", nullable = false)
    val eventType: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    val payload: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: OutboxStatus,
    @Column(nullable = false)
    var attempts: Int,
    @Column(name = "next_retry_at", nullable = false)
    var nextRetryAt: Instant,
    @Column(name = "last_error")
    var lastError: String?,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "delivered_at")
    var deliveredAt: Instant?,
) {
    protected constructor() : this(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "",
        "{}",
        OutboxStatus.PENDING,
        0,
        Instant.now(),
        null,
        Instant.now(),
        null,
    )
}
