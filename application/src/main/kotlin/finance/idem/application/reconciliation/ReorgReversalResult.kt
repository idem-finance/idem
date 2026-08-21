package finance.idem.application.reconciliation

import finance.idem.core.TransactionId
import finance.idem.core.ledger.Settlement

sealed class ReorgReversalResult {
    data class Reversed(
        val settlement: Settlement,
        val reversalTransactionId: TransactionId,
    ) : ReorgReversalResult()

    /** No settlement was ever matched/posted for this (txHash, logIndex) — nothing to reverse. */
    data object NoMatchingSettlement : ReorgReversalResult()

    /** Already reversed by an earlier delivery — idempotent no-op. */
    data object AlreadyReorged : ReorgReversalResult()
}
