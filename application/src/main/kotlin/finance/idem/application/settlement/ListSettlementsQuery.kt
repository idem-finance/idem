package finance.idem.application.settlement

import finance.idem.core.TenantId
import finance.idem.core.ledger.EntryStatus
import java.time.Instant

data class ListSettlementsQuery(
    val tenantId: TenantId,
    val status: EntryStatus?,
    val from: Instant?,
    val to: Instant?,
    val limit: Int,
    val cursor: String?,
)
