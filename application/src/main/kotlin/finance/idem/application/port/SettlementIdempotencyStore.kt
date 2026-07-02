package finance.idem.application.port

import finance.idem.core.TenantId
import java.util.UUID

interface SettlementIdempotencyStore {
    fun find(
        key: String,
        tenantId: TenantId,
    ): UUID?

    /**
     * Atomically inserts (key, tenantId) → settlementId using INSERT ON CONFLICT at the DB level.
     * Returns true if the key was newly claimed; false if it already existed.
     * Must be called BEFORE the settlement is persisted to prevent concurrent duplicate registrations.
     */
    fun tryRecord(
        key: String,
        tenantId: TenantId,
        settlementId: UUID,
    ): Boolean
}
