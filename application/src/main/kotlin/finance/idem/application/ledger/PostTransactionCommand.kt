package finance.idem.application.ledger

import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.TenantId
import finance.idem.core.agentic.AgentContext
import finance.idem.core.monetary.MonetaryEntry

data class PostTransactionCommand(
    val tenantId: TenantId,
    val idempotencyKey: String,
    val lines: List<JournalLineRequest>,
    val createdBy: String,
    val agentContext: AgentContext? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class JournalLineRequest(
    val accountId: AccountId,
    val entryType: EntryType,
    val monetaryEntry: MonetaryEntry,
    val description: String? = null,
)
