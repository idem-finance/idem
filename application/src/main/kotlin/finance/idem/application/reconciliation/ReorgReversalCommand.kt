package finance.idem.application.reconciliation

import finance.idem.core.TenantId

data class ReorgReversalCommand(
    val tenantId: TenantId,
    val txHash: String,
    val logIndex: Int,
    val chainKey: String,
    val reason: String,
)
