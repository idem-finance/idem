package finance.idem.infrastructure.persistence.idempotency

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "idempotency_keys")
class IdempotencyKeyDataModel(
    @Id
    val id: UUID,

    @Column(name = "tenant_id", nullable = false)
    val tenantId: UUID,

    @Column(name = "idempotency_key", nullable = false)
    val idempotencyKey: String,

    @Column(name = "transaction_id", nullable = false)
    val transactionId: UUID,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
) {
    protected constructor() : this(UUID.randomUUID(), UUID.randomUUID(), "", UUID.randomUUID(), Instant.now())
}
