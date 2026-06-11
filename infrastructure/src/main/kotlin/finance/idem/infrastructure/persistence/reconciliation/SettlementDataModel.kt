package finance.idem.infrastructure.persistence.reconciliation

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "settlements")
class SettlementDataModel(
    @Id val id: UUID,
    @Column(name = "tenant_id", nullable = false) val tenantId: UUID,
    @Column(name = "account_id", nullable = false) val accountId: UUID,
    @Column(nullable = false, precision = 38, scale = 18) val amount: BigDecimal,
    @Column(nullable = false) val token: String,
    @Column(name = "chain_id", nullable = false) val chainId: String,
    @Column(name = "wallet_address", nullable = false) val walletAddress: String,
    @Column(nullable = false) val status: String,
    @Column(name = "matched_transaction_id") val matchedTransactionId: UUID?,
    @Column(name = "tx_hash") val txHash: String?,
    @Column(name = "block_number") val blockNumber: Long?,
    @Column(name = "confirmed_at") val confirmedAt: Instant?,
    @Column(name = "expected_from_address") val expectedFromAddress: String?,
    @Column(name = "created_at", nullable = false, updatable = false) val createdAt: Instant,
    @Column(name = "created_by", nullable = false, updatable = false) val createdBy: String,
) {
    protected constructor() : this(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        BigDecimal.ZERO, "", "", "", "PENDING",
        null, null, null, null, null, Instant.now(), "",
    )
}
