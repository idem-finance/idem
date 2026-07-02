package finance.idem.application.audit

import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.agentic.AgentContext
import finance.idem.core.ledger.Transaction
import java.time.Instant
import java.util.UUID

data class AuditEntry(
    val id: UUID,
    val transactionId: TransactionId,
    val tenantId: TenantId,
    val action: String,
    val agentContext: AgentContext?,
    val createdBy: String,
    val occurredAt: Instant,
) {
    companion object {
        fun from(
            tx: Transaction,
            agentContext: AgentContext?,
            createdBy: String,
        ): AuditEntry =
            AuditEntry(
                id = UUID.randomUUID(),
                transactionId = tx.id,
                tenantId = tx.tenantId,
                action = "POST_TRANSACTION",
                agentContext = agentContext,
                createdBy = createdBy,
                occurredAt = tx.createdAt,
            )
    }
}
