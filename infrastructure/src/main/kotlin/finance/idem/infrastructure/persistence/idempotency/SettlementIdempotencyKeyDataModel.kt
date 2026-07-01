package finance.idem.infrastructure.persistence.idempotency

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "settlement_idempotency_keys")
@IdClass(SettlementIdempotencyKeyId::class)
class SettlementIdempotencyKeyDataModel(
    @Id
    @Column(name = "tenant_id", nullable = false)
    val tenantId: UUID,
    @Id
    @Column(name = "key", nullable = false)
    val key: String,
    @Column(name = "settlement_id", nullable = false)
    val settlementId: UUID,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
) {
    protected constructor() : this(UUID.randomUUID(), "", UUID.randomUUID(), Instant.now())
}
