package finance.idem.application.reconciliation

import finance.idem.core.ledger.Transaction

interface BasicReconciliationUseCase {
    /** Must be called within the same @Transactional as the transaction's
     * primary persistence (no event bus). */
    fun reconcile(transaction: Transaction): ReconciliationResult
}
