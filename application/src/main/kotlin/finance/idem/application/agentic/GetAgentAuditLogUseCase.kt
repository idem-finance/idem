package finance.idem.application.agentic

import finance.idem.application.port.AgentAuditView

interface GetAgentAuditLogUseCase {
    fun execute(query: GetAgentAuditLogQuery): List<AgentAuditView>
}
