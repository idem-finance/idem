package finance.idem.application.ledger

import finance.idem.core.TenantId
import finance.idem.core.agentic.AgentContext

data class PostTransactionCommand(
    val tenantId: TenantId,
    val idempotencyKey: String,
    val lines: List<JournalLineRequest>,
    val createdBy: String,
    val agentContext: AgentContext? = null,
    val metadata: Map<String, String> = emptyMap(),
)
