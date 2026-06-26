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
@Table(name = "travel_rule_data")
class TravelRuleDataDataModel(
    @Id
    val id: UUID,

    @Column(name = "tenant_id", nullable = false)
    val tenantId: UUID,

    @Column(name = "transfer_id", nullable = false)
    val transferId: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    val originator: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    val beneficiary: String,

    @Column(name = "transfer_amount", nullable = false)
    val transferAmount: BigDecimal,

    @Column(name = "transfer_asset", nullable = false)
    val transferAsset: String,

    @Column(nullable = false)
    val threshold: BigDecimal,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    protected constructor() : this(
        UUID.randomUUID(), UUID.randomUUID(), "", "{}", "{}",
        BigDecimal.ZERO, "USDC", BigDecimal.ONE, Instant.now(),
    )
}
