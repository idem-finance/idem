package finance.idem.application.reconciliation

import finance.idem.core.TransactionId

data class ReconcileBatchItemResult(
    val transactionId: TransactionId,
    val outcome: ReconcileOutcome,
)

enum class ReconcileOutcome { SETTLED, UNMATCHED, NOT_APPLICABLE, NOT_FOUND }
