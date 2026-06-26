package finance.idem.application.port

import finance.idem.application.compliance.ComplianceQueueItem
import finance.idem.core.TenantId

interface ComplianceQueueRepository {
    fun enqueue(item: ComplianceQueueItem, tenantId: TenantId)
}
