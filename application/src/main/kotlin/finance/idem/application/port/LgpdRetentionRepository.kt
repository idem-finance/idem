package finance.idem.application.port

import finance.idem.core.TenantId

interface LgpdRetentionRepository {
    fun schedule(tenantId: TenantId, entityType: String, entityId: String, retentionYears: Int)
}
