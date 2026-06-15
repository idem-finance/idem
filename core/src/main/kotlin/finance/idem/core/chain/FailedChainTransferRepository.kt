package finance.idem.core.chain

interface FailedChainTransferRepository {
    /**
     * Idempotent — a row with the same [FailedChainTransfer.idempotencyKey] is not duplicated.
     *
     * On conflict, the existing row wins: if the same transfer is recorded again (e.g. a
     * retried recovery sweep) with a different [FailedChainTransfer.errorMessage], the row
     * from the *first* failure is left unchanged, not overwritten.
     */
    fun save(transfer: FailedChainTransfer)
}
