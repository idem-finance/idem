package finance.idem.application.port

import finance.idem.core.TenantId
import finance.idem.core.agentic.AgentAuditEvent
import java.time.Instant

interface AgentAuditRepository {
    fun save(event: AgentAuditEvent)

    fun findByFilter(
        tenantId: TenantId,
        sessionId: String? = null,
        from: Instant? = null,
        to: Instant? = null,
        limit: Int = 50,
    ): List<AgentAuditView>
}
