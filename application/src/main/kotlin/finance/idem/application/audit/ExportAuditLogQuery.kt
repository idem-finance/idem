package finance.idem.application.audit

import finance.idem.core.TenantId
import java.time.Instant

data class ExportAuditLogQuery(
    val tenantId: TenantId,
    val from: Instant,
    val to: Instant,
    val type: AuditEntryType,
)
