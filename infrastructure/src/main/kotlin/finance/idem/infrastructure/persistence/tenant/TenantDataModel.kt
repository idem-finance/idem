package finance.idem.infrastructure.persistence.tenant

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "tenants")
class TenantDataModel(
    @Id
    val id: UUID,
    @Column(name = "webhook_url")
    val webhookUrl: String?,
    @Column(name = "webhook_secret")
    val webhookSecret: String?,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant,
) {
    protected constructor() : this(UUID.randomUUID(), null, null, Instant.now(), Instant.now())
}
