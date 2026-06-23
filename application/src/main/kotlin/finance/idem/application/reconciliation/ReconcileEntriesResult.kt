package finance.idem.application.reconciliation

data class ReconcileEntriesResult(
    val matched: Int,
    val unmatched: Int,
    val exceptions: List<ReconciliationException>,
)
