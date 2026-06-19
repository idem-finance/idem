package finance.idem.application.reconciliation

import finance.idem.core.TenantId
import finance.idem.core.TransactionId

data class ReconcileBatchCommand(
    val transactionIds: List<TransactionId>,
    val tenantId: TenantId,
)
