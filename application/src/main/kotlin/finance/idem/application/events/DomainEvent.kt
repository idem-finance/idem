package finance.idem.application.events

import finance.idem.core.TenantId
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.WorkflowPlan
import finance.idem.core.events.DomainEventReferenceType
import finance.idem.core.events.DomainEventType
import finance.idem.core.ledger.Settlement
import finance.idem.core.ledger.Transaction
import java.time.Instant
import java.util.UUID

data class DomainEvent(
    val id: UUID,
    val tenantId: TenantId,
    val eventType: DomainEventType,
    val referenceId: UUID,
    val referenceType: DomainEventReferenceType,
    val correlationId: String,
    val occurredAt: Instant,
) {
    companion object {
        fun transactionCommitted(
            tx: Transaction,
            correlationId: String,
        ): DomainEvent =
            DomainEvent(
                id = UUID.randomUUID(),
                tenantId = tx.tenantId,
                eventType = DomainEventType.TRANSACTION_COMMITTED,
                referenceId = tx.id.value,
                referenceType = DomainEventReferenceType.TRANSACTION,
                correlationId = correlationId,
                occurredAt = tx.occurredAt,
            )

        fun transactionSettled(
            tx: Transaction,
            correlationId: String,
        ): DomainEvent =
            DomainEvent(
                id = UUID.randomUUID(),
                tenantId = tx.tenantId,
                eventType = DomainEventType.TRANSACTION_SETTLED,
                referenceId = tx.id.value,
                referenceType = DomainEventReferenceType.TRANSACTION,
                correlationId = correlationId,
                occurredAt = tx.occurredAt,
            )

        /** Emitted by ReconcileEntriesUseCase when a sweep retroactively settles an UNMATCHED entry. */
        fun transactionSettled(
            settlement: Settlement,
            correlationId: String,
        ): DomainEvent =
            DomainEvent(
                id = UUID.randomUUID(),
                tenantId = settlement.tenantId,
                eventType = DomainEventType.TRANSACTION_SETTLED,
                referenceId =
                    requireNotNull(settlement.matchedTransactionId) {
                        "UNMATCHED settlement ${settlement.id} must have matchedTransactionId"
                    }.value,
                referenceType = DomainEventReferenceType.TRANSACTION,
                correlationId = correlationId,
                occurredAt = settlement.confirmedAt ?: Instant.now(),
            )

        fun reconciliationUnmatched(
            tx: Transaction,
            correlationId: String,
        ): DomainEvent =
            DomainEvent(
                id = UUID.randomUUID(),
                tenantId = tx.tenantId,
                eventType = DomainEventType.RECONCILIATION_UNMATCHED,
                referenceId = tx.id.value,
                referenceType = DomainEventReferenceType.TRANSACTION,
                correlationId = correlationId,
                occurredAt = tx.occurredAt,
            )

        /** Emitted by ReconcileEntriesUseCase when a sweep attempt still finds no PENDING match. */
        fun reconciliationException(
            settlement: Settlement,
            correlationId: String,
        ): DomainEvent =
            DomainEvent(
                id = UUID.randomUUID(),
                tenantId = settlement.tenantId,
                eventType = DomainEventType.RECONCILIATION_EXCEPTION,
                referenceId =
                    requireNotNull(settlement.matchedTransactionId) {
                        "UNMATCHED settlement ${settlement.id} must have matchedTransactionId"
                    }.value,
                referenceType = DomainEventReferenceType.TRANSACTION,
                correlationId = correlationId,
                occurredAt = settlement.confirmedAt ?: Instant.now(),
            )

        fun workflowCommitted(
            plan: WorkflowPlan,
            correlationId: String,
        ): DomainEvent =
            DomainEvent(
                id = UUID.randomUUID(),
                tenantId = plan.tenantId,
                eventType = DomainEventType.WORKFLOW_COMMITTED,
                referenceId = plan.id.value,
                referenceType = DomainEventReferenceType.WORKFLOW,
                correlationId = correlationId,
                occurredAt = requireNotNull(plan.completedAt) { "COMMITTED plan must have completedAt" },
            )

        fun workflowRolledBack(
            plan: WorkflowPlan,
            correlationId: String,
        ): DomainEvent =
            DomainEvent(
                id = UUID.randomUUID(),
                tenantId = plan.tenantId,
                eventType = DomainEventType.WORKFLOW_ROLLED_BACK,
                referenceId = plan.id.value,
                referenceType = DomainEventReferenceType.WORKFLOW,
                correlationId = correlationId,
                occurredAt = Instant.now(),
            )

        fun travelRuleRequired(
            tx: Transaction,
            correlationId: String,
        ): DomainEvent =
            DomainEvent(
                id = UUID.randomUUID(),
                tenantId = tx.tenantId,
                eventType = DomainEventType.COMPLIANCE_TRAVEL_RULE_REQUIRED,
                referenceId = tx.id.value,
                referenceType = DomainEventReferenceType.TRANSACTION,
                correlationId = correlationId,
                occurredAt = Instant.now(),
            )

        /** Emitted only from the ExecuteWorkflowService PolicyGuard-denial path. */
        fun agentActionFlagged(
            workflowPlanId: WorkflowPlanId,
            tenantId: TenantId,
            correlationId: String,
            occurredAt: Instant = Instant.now(),
        ): DomainEvent =
            DomainEvent(
                id = UUID.randomUUID(),
                tenantId = tenantId,
                eventType = DomainEventType.AGENT_ACTION_FLAGGED,
                referenceId = workflowPlanId.value,
                referenceType = DomainEventReferenceType.WORKFLOW,
                correlationId = correlationId,
                occurredAt = occurredAt,
            )
    }
}
