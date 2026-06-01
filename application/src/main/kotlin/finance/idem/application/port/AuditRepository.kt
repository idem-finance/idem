package finance.idem.application.port

import finance.idem.application.audit.AuditEntry

interface AuditRepository {
    fun save(entry: AuditEntry)
}
