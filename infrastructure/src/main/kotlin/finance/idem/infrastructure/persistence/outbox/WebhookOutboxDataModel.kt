package finance.idem.infrastructure.persistence.outbox

import jakarta.persistence.Column
import jakarta.persistence.Entity
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

    @Column(nullable = false)
    var dispatched: Boolean,

    @Column(name = "retry_count", nullable = false)
    var retryCount: Int,

    @Column(name = "last_error")
    var lastError: String?,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "dispatched_at")
    var dispatchedAt: Instant?,
) {
    protected constructor() : this(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        "", "{}", false, 0, null, Instant.now(), null,
    )
}
