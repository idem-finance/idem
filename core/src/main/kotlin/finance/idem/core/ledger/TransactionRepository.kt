package finance.idem.core.ledger

import finance.idem.core.AccountId
import finance.idem.core.TenantId
import finance.idem.core.TransactionId

interface TransactionRepository {
    fun findById(id: TransactionId, tenantId: TenantId): Transaction?
    fun save(transaction: Transaction): Transaction
    fun findByIdempotencyKey(key: String, tenantId: TenantId): Transaction?
    fun findByAccountId(accountId: AccountId, tenantId: TenantId): List<Transaction>
}
