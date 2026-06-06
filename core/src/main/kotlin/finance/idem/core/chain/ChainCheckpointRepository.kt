package finance.idem.core.chain

interface ChainCheckpointRepository {
    fun findByChainKey(chainKey: String): ChainCheckpoint?
    fun save(chainKey: String, lastBlock: Long)
}
