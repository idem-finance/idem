package finance.idem.api.internal

import java.time.Instant
import java.util.UUID

data class SuspendTenantResponse(
    val tenantId: UUID,
    val suspendedAt: Instant,
)
