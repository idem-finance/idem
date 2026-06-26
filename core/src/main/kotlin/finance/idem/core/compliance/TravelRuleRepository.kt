package finance.idem.core.compliance

import finance.idem.core.TenantId

interface TravelRuleRepository {
    fun save(data: TravelRuleData, tenantId: TenantId): TravelRuleData
    fun findByTransferId(transferId: String, tenantId: TenantId): TravelRuleData?
}
