package finance.idem.application.port

import finance.idem.application.audit.AuditExportRecord
import finance.idem.application.audit.ExportAuditLogQuery

interface AuditExportRepository {
    fun findForExport(query: ExportAuditLogQuery): List<AuditExportRecord>
}
