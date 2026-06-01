package finance.idem.application.port

import finance.idem.core.TenantId
import finance.idem.core.TransactionId

interface IdempotencyStore {
    fun find(key: String, tenantId: TenantId): TransactionId?
    fun record(key: String, tenantId: TenantId, transactionId: TransactionId)
}
