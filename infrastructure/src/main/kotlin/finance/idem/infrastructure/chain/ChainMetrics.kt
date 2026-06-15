package finance.idem.infrastructure.chain

/**
 * Shared Micrometer metric names/tags for chain reader alerting.
 *
 * `idem.chain.dead_letter` is incremented whenever a detected on-chain transfer fails to post
 * (see [finance.idem.core.chain.FailedChainTransfer]) — alert on a non-zero rate of this counter.
 */
internal object ChainMetrics {
    const val DEAD_LETTER_COUNTER = "idem.chain.dead_letter"
    const val TAG_CHAIN_KEY = "chain_key"
    const val TAG_SOURCE = "source"
}
