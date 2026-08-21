package finance.idem.application.outbox

import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.agentic.WorkflowPlan
import finance.idem.core.ledger.Settlement
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

        /** Emitted by ReconcileEntriesUseCase when a sweep retroactively settles an UNMATCHED entry. */
        fun transactionSettled(settlement: Settlement): WebhookOutboxEntry =
            WebhookOutboxEntry(
                id = UUID.randomUUID(),
                tenantId = settlement.tenantId,
                eventType = "transaction.settled",
                transactionId =
                    requireNotNull(settlement.matchedTransactionId) {
                        "UNMATCHED settlement ${settlement.id} must have matchedTransactionId"
                    },
                occurredAt = settlement.confirmedAt ?: Instant.now(),
            )

        /** Emitted by ReorgReversalService when a chain reorg reverses a previously
         * matched/settled entry via a compensating transaction. */
        fun settlementReorged(settlement: Settlement): WebhookOutboxEntry =
            WebhookOutboxEntry(
                id = UUID.randomUUID(),
                tenantId = settlement.tenantId,
                eventType = "settlement.reorged",
                transactionId =
                    requireNotNull(settlement.reversalTransactionId) {
                        "REORGED settlement ${settlement.id} must have reversalTransactionId"
                    },
                occurredAt = settlement.reorgedAt ?: Instant.now(),
            )

        fun reconciliationUnmatched(tx: Transaction): WebhookOutboxEntry =
            WebhookOutboxEntry(
                id = UUID.randomUUID(),
                tenantId = tx.tenantId,
                eventType = "reconciliation.unmatched",
                transactionId = tx.id,
                occurredAt = tx.occurredAt,
            )

        /** Emitted by ReconcileEntriesUseCase when a sweep attempt still finds no PENDING match. */
        fun reconciliationException(settlement: Settlement): WebhookOutboxEntry =
            WebhookOutboxEntry(
                id = UUID.randomUUID(),
                tenantId = settlement.tenantId,
                eventType = "reconciliation.exception",
                transactionId =
                    requireNotNull(settlement.matchedTransactionId) {
                        "UNMATCHED settlement ${settlement.id} must have matchedTransactionId"
                    },
                occurredAt = settlement.confirmedAt ?: Instant.now(),
            )

        // workflow events reuse the transactionId UUID column to store workflowPlanId;
        // eventType disambiguates for consumers.
        fun workflowCommitted(plan: WorkflowPlan): WebhookOutboxEntry =
            WebhookOutboxEntry(
                id = UUID.randomUUID(),
                tenantId = plan.tenantId,
                eventType = "workflow.committed",
                transactionId = TransactionId(plan.id.value),
                occurredAt = requireNotNull(plan.completedAt) { "COMMITTED plan must have completedAt" },
            )

        fun workflowRolledBack(plan: WorkflowPlan): WebhookOutboxEntry =
            WebhookOutboxEntry(
                id = UUID.randomUUID(),
                tenantId = plan.tenantId,
                eventType = "workflow.rolled_back",
                transactionId = TransactionId(plan.id.value),
                occurredAt = Instant.now(),
            )

        fun travelRuleRequired(tx: Transaction): WebhookOutboxEntry =
            WebhookOutboxEntry(
                id = UUID.randomUUID(),
                tenantId = tx.tenantId,
                eventType = "compliance.travel_rule_required",
                transactionId = tx.id,
                occurredAt = Instant.now(),
            )
    }
}
