package finance.idem.application.outbox

import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.agentic.WorkflowPlan
import finance.idem.core.ledger.Transaction
import java.time.Instant
import java.util.UUID

data class WebhookOutboxEntry(
    val id: UUID,
    val tenantId: TenantId,
    val eventType: String,
    val transactionId: TransactionId,
    val occurredAt: Instant,
) {
    companion object {
        fun transactionCommitted(tx: Transaction): WebhookOutboxEntry =
            WebhookOutboxEntry(
                id = UUID.randomUUID(),
                tenantId = tx.tenantId,
                eventType = "transaction.committed",
                transactionId = tx.id,
                occurredAt = tx.occurredAt,
            )

        fun transactionSettled(tx: Transaction): WebhookOutboxEntry =
            WebhookOutboxEntry(
                id = UUID.randomUUID(),
                tenantId = tx.tenantId,
                eventType = "transaction.settled",
                transactionId = tx.id,
                occurredAt = tx.occurredAt,
            )

        fun reconciliationUnmatched(tx: Transaction): WebhookOutboxEntry =
            WebhookOutboxEntry(
                id = UUID.randomUUID(),
                tenantId = tx.tenantId,
                eventType = "reconciliation.unmatched",
                transactionId = tx.id,
                occurredAt = tx.occurredAt,
            )

        // workflow events reuse the transactionId UUID column to store workflowPlanId;
        // eventType disambiguates for consumers.
        fun workflowCommitted(plan: WorkflowPlan): WebhookOutboxEntry =
            WebhookOutboxEntry(
                id = UUID.randomUUID(),
                tenantId = plan.tenantId,
                eventType = "workflow.committed",
                transactionId = TransactionId(plan.id.value),
                occurredAt = plan.committedAt ?: Instant.now(),
            )

        fun workflowRolledBack(plan: WorkflowPlan): WebhookOutboxEntry =
            WebhookOutboxEntry(
                id = UUID.randomUUID(),
                tenantId = plan.tenantId,
                eventType = "workflow.rolled_back",
                transactionId = TransactionId(plan.id.value),
                occurredAt = Instant.now(),
            )
    }
}
