package finance.idem.infrastructure.persistence.telemetry

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "installation_metadata")
class InstallationMetadataDataModel(
    @Id
    @Column(name = "singleton", nullable = false)
    val singleton: Int,

    @Column(name = "id", nullable = false)
    val id: UUID,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    protected constructor() : this(0, UUID(0L, 0L), Instant.EPOCH)
}
