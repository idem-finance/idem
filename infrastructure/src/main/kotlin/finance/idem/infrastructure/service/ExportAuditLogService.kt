package finance.idem.infrastructure.service

import finance.idem.application.audit.AuditExportRecord
import finance.idem.application.audit.ExportAuditLogQuery
import finance.idem.application.audit.ExportAuditLogUseCase
import finance.idem.application.port.AuditExportRepository
import org.springframework.stereotype.Service

@Service
class ExportAuditLogService(
    private val auditExportRepository: AuditExportRepository,
) : ExportAuditLogUseCase {
    override fun export(query: ExportAuditLogQuery): List<AuditExportRecord> = auditExportRepository.findForExport(query)
}
