package finance.idem.infrastructure.persistence.chain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "chain_checkpoint")
class ChainCheckpointDataModel(
    @Id
    @Column(name = "chain_id", nullable = false)
    val chainId: String,
    @Column(name = "last_block", nullable = false)
    val lastBlock: Long,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant,
) {
    protected constructor() : this("", 0L, Instant.now())
}
