package finance.idem.application.audit

interface ExportAuditLogUseCase {
    fun export(query: ExportAuditLogQuery): List<AuditExportRecord>
}
