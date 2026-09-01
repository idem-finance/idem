package finance.idem.core.tenant

import finance.idem.core.TenantId
import java.time.Instant

data class Tenant(
    val id: TenantId,
    val organizationName: String,
    val contactEmail: String,
    val createdAt: Instant,
)
