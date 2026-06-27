package finance.idem.application.port

import finance.idem.application.compliance.ComplianceQueueItem

interface ComplianceQueueRepository {
    fun enqueue(item: ComplianceQueueItem)
}
