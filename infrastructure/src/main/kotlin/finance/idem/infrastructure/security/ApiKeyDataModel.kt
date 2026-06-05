package finance.idem.infrastructure.security

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "api_keys")
class ApiKeyDataModel(
    @Id
    val id: UUID,

    @Column(name = "tenant_id", nullable = false)
    val tenantId: UUID,

    @Column(name = "key_hash", nullable = false)
    val keyHash: String,

    @Column(name = "prefix", length = 12, nullable = false)
    val prefix: String,

    // Comma-delimited ApiScope names — e.g. "TRANSACTIONS_READ,ACCOUNTS_READ"
    @Column(name = "scopes", nullable = false)
    val scopes: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "revoked_at")
    val revokedAt: Instant?,
) {
    protected constructor() : this(
        UUID.randomUUID(), UUID.randomUUID(), "", "", "", Instant.now(), null,
    )
}
