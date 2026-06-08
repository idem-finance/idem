package finance.idem.infrastructure.chain

interface WatchedAddressRepository {
    fun findByChainKey(chainKey: String): List<WatchedAddress>
}
