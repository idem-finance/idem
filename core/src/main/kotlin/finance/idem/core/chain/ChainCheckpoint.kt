package finance.idem.core.chain

import java.time.Instant

data class ChainCheckpoint(
    val chainKey: String,
    val lastBlock: Long,
    val updatedAt: Instant,
)
