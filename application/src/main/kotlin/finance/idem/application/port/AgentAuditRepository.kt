package finance.idem.application.port

import finance.idem.core.agentic.AgentAuditEvent

interface AgentAuditRepository {
    fun save(event: AgentAuditEvent)
}
