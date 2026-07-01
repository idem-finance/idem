package finance.idem.infrastructure.persistence

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "transactions")
class TransactionDataModel(
    @Id
    val id: UUID,
    @Column(name = "tenant_id", nullable = false)
    val tenantId: UUID,
    @Column(name = "idempotency_key", nullable = false)
    val idempotencyKey: String,
    @Column(nullable = false)
    val status: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "agent_context", columnDefinition = "jsonb")
    val agentContext: String?,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    val metadata: String,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "created_by", nullable = false)
    val createdBy: String,
    @OneToMany(mappedBy = "transaction", cascade = [CascadeType.ALL], fetch = FetchType.EAGER, orphanRemoval = true)
    val lines: MutableList<JournalLineDataModel> = mutableListOf(),
) {
    constructor() : this(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "",
        "PENDING",
        null,
        "{}",
        Instant.now(),
        Instant.now(),
        "",
    )
}
