package finance.idem.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "accounts")
class AccountDataModel(
    @Id
    val id: UUID,
    @Column(name = "tenant_id", nullable = false)
    val tenantId: UUID,
    @Column(nullable = false)
    val name: String,
    val description: String?,
    @Column(nullable = false)
    val currency: String,
    @Column(nullable = false)
    val type: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "created_by", nullable = false)
    val createdBy: String,
    @Column(name = "updated_at")
    val updatedAt: Instant?,
    @Column(name = "updated_by")
    val updatedBy: String?,
) {
    protected constructor() : this(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "",
        null,
        "",
        "",
        Instant.now(),
        "",
        null,
        null,
    )
}
