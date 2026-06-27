package finance.idem.infrastructure.compliance

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "compliance_queue")
class ComplianceQueueDataModel(
    @Id
    val id: UUID,

    @Column(name = "tenant_id", nullable = false)
    val tenantId: UUID,

    @Column(name = "tx_hash", nullable = false)
    val txHash: String,

    @Column(name = "chain_id", nullable = false)
    val chainId: String,

    @Column(name = "entry_amount", nullable = false)
    val entryAmount: BigDecimal,

    @Column(nullable = false)
    val reason: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "missing_fields", columnDefinition = "jsonb", nullable = false)
    val missingFields: String,

    @Column(nullable = false)
    val status: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    protected constructor() : this(
        UUID.randomUUID(), UUID.randomUUID(), "", "EVM",
        BigDecimal.ZERO, "MISSING_DATA", "[]", "PENDING", Instant.now(),
    )
}
