package finance.idem.infrastructure.persistence.chain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "watched_addresses")
class WatchedAddressDataModel(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID,

    @Column(name = "chain_key", nullable = false)
    val chainKey: String,

    @Column(name = "wallet_address", nullable = false)
    val walletAddress: String,

    @Column(name = "token_contract", nullable = false)
    val tokenContract: String,

    @Column(name = "token", nullable = false)
    val token: String,

    @Column(name = "tenant_id", nullable = false)
    val tenantId: UUID,

    @Column(name = "debit_account_id", nullable = false)
    val debitAccountId: UUID,

    @Column(name = "credit_account_id", nullable = false)
    val creditAccountId: UUID,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    protected constructor() : this(
        UUID.randomUUID(), "", "", "", "",
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
    )
}
