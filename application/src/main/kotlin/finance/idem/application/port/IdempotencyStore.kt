package finance.idem.application.port

import finance.idem.core.TenantId
import finance.idem.core.TransactionId

interface IdempotencyStore {
    fun find(key: String, tenantId: TenantId): TransactionId?

    /**
     * Atomically inserts (key, tenantId) → transactionId using INSERT ON CONFLICT at the DB level.
     * Returns true if the key was newly claimed; false if it already existed.
     * Must be called BEFORE any ledger writes to prevent concurrent duplicate transactions.
     */
    fun tryRecord(key: String, tenantId: TenantId, transactionId: TransactionId): Boolean

    /** Removes a claimed key — used when a ROLLED_BACK transaction allows safe retry. */
    fun release(key: String, tenantId: TenantId)
}
