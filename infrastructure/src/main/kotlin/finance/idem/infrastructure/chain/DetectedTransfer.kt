package finance.idem.infrastructure.chain

import finance.idem.core.monetary.OnChainEntry

data class DetectedTransfer(
    val idempotencyKey: String,
    val entry: OnChainEntry,
    val watchedAddress: WatchedAddress,
)
