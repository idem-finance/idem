package finance.idem.application.audit

import java.time.Instant
import java.util.UUID

data class AuditExportRecord(
    val timestamp: Instant,
    val actor: String,
    val action: String,
    val entityType: String,
    val entityId: UUID,
    val intentDescription: String?,
    val hmacSignature: String,
    val outcome: String?,
)
