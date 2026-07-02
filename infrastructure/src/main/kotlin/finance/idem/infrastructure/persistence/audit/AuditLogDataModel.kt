package finance.idem.infrastructure.persistence.audit

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "audit_log")
class AuditLogDataModel(
    @Id
    val id: UUID,
    @Column(name = "tenant_id", nullable = false)
    val tenantId: UUID,
    @Column(name = "transaction_id", nullable = false)
    val transactionId: UUID,
    @Column(name = "agent_id")
    val agentId: String?,
    val intent: String?,
    @Column(nullable = false)
    val action: String,
    @Column(name = "created_by", nullable = false)
    val createdBy: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    val payload: String,
    @Column(nullable = false)
    val hmac: String,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
) {
    protected constructor() : this(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        null,
        "",
        "",
        "{}",
        "",
        Instant.now(),
    )
}
