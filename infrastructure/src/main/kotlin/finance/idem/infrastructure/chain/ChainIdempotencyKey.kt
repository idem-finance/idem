package finance.idem.infrastructure.chain

/**
 * Single source of truth for the chain-entry idempotency key format. `release()` (on reorg
 * reversal) must produce byte-for-byte the same key `tryRecord()` originally stored — an
 * isolated edit to an inline reconstruction of this format at any call site would silently
 * break that correlation with no test catching it, since each site was previously tested in
 * isolation.
 */
object ChainIdempotencyKey {
    fun of(
        chainKey: String,
        txHash: String,
        logIndex: Int,
    ): String = "$chainKey:$txHash:$logIndex"
}
