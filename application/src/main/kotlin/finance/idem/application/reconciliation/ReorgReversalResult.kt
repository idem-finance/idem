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

    /** The original transaction was already compensated by an operator/agent-initiated
     * RollbackWorkflowService rollback — the ledger is already balanced, so no new compensating
     * transaction was posted. The settlement is still flagged REORGED, reusing the rollback's
     * compensating transaction id as reversalTransactionId, so the reorg remains visible for audit. */
    data class AlreadyCompensatedByRollback(
        val settlement: Settlement,
        val rollbackTransactionId: TransactionId,
    ) : ReorgReversalResult()
}
