package finance.idem.infrastructure.service

import finance.idem.application.agentic.GetAgentAuditLogQuery
import finance.idem.application.agentic.GetAgentAuditLogUseCase
import finance.idem.application.port.AgentAuditRepository
import finance.idem.application.port.AgentAuditView
import org.springframework.stereotype.Service

@Service
class GetAgentAuditLogService(
    private val agentAuditRepository: AgentAuditRepository,
) : GetAgentAuditLogUseCase {

    override fun execute(query: GetAgentAuditLogQuery): List<AgentAuditView> =
        agentAuditRepository.findByFilter(
            tenantId = query.tenantId,
            sessionId = query.sessionId,
            from = query.from,
            to = query.to,
            limit = query.limit.coerceIn(1, 200),
        )
}
