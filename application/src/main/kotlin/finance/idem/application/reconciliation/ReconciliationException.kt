package finance.idem.application.reconciliation

import java.util.UUID

data class ReconciliationException(
    val settlementId: UUID,
    val txHash: String?,
    val reason: String,
)
