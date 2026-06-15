package finance.idem.core.chain

interface FailedChainTransferRepository {
    /**
     * Idempotent — a row with the same [FailedChainTransfer.idempotencyKey] is not duplicated.
     */
    fun save(transfer: FailedChainTransfer)
}
