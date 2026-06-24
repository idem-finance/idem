package finance.idem.application.agentic

import finance.idem.core.TenantId
import java.time.Instant

data class GetAgentAuditLogQuery(
    val tenantId: TenantId,
    val sessionId: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val limit: Int = 50,
)
