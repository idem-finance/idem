package finance.idem.infrastructure.persistence.chain

import finance.idem.core.StablecoinToken
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "failed_chain_transfers")
class FailedChainTransferDataModel(
    @Id
    val id: UUID,

    @Column(name = "chain_key", nullable = false)
    val chainKey: String,

    @Column(nullable = false)
    val source: String,

    @Column(name = "idempotency_key", nullable = false)
    val idempotencyKey: String,

    @Column(name = "tx_hash", nullable = false)
    val txHash: String,

    @Column(name = "block_number", nullable = false)
    val blockNumber: Long,

    @Column(name = "tenant_id", nullable = false)
    val tenantId: UUID,

    @Column(name = "wallet_address", nullable = false)
    val walletAddress: String,

    @Column(name = "token_contract", nullable = false)
    val tokenContract: String,

    @Column(name = "debit_account_id", nullable = false)
    val debitAccountId: UUID,

    @Column(name = "credit_account_id", nullable = false)
    val creditAccountId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val token: StablecoinToken,

    @Column(nullable = false)
    val amount: BigDecimal,

    @Column(name = "error_message", nullable = false)
    val errorMessage: String,

    @Column(nullable = false)
    val resolved: Boolean,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "resolved_at")
    val resolvedAt: Instant?,
) {
    protected constructor() : this(
        UUID.randomUUID(), "", "", "", "", 0L, UUID.randomUUID(), "", "",
        UUID.randomUUID(), UUID.randomUUID(), StablecoinToken.USDC, BigDecimal.ZERO,
        "", false, Instant.now(), null,
    )
}
