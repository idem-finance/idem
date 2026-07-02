package finance.idem.application.reconciliation

import finance.idem.core.ledger.Settlement

sealed class ReconciliationResult {
    data object NotApplicable : ReconciliationResult()

    data class Settled(
        val settlement: Settlement,
    ) : ReconciliationResult()

    data class Unmatched(
        val settlement: Settlement,
    ) : ReconciliationResult()
}
