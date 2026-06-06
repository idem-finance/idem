package finance.idem.infrastructure.persistence.chain

import finance.idem.core.chain.ChainCheckpoint
import finance.idem.core.chain.ChainCheckpointRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class ChainCheckpointRepositoryAdapter(
    private val jpaRepository: ChainCheckpointJpaRepository,
) : ChainCheckpointRepository {

    @Transactional(readOnly = true)
    override fun findByChainKey(chainKey: String): ChainCheckpoint? =
        jpaRepository.findById(chainKey).orElse(null)?.toDomain()

    @Transactional
    override fun save(chainKey: String, lastBlock: Long) {
        jpaRepository.save(ChainCheckpointDataModel(chainKey, lastBlock, Instant.now()))
    }
}

private fun ChainCheckpointDataModel.toDomain() = ChainCheckpoint(
    chainKey = chainId,
    lastBlock = lastBlock,
    updatedAt = updatedAt,
)
