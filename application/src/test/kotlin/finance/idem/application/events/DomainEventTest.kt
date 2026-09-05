package finance.idem.application.events

import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.AgentContext
import finance.idem.core.agentic.WorkflowPlan
import finance.idem.core.events.DomainEventReferenceType
import finance.idem.core.events.DomainEventType
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.JournalLine
import finance.idem.core.ledger.Settlement
import finance.idem.core.ledger.Transaction
import finance.idem.core.monetary.FiatEntry
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class DomainEventTest {
    private val tenantId = TenantId.generate()
    private val txId = TransactionId(UUID.randomUUID())
    private val now = Instant.parse("2025-10-01T00:00:00Z")
    private val correlationId = "trace-abc-123"

    private fun ledgerTx(): Transaction {
        val debitLine =
            JournalLine(
                id = UUID.randomUUID(),
                transactionId = txId,
                accountId = AccountId.generate(),
                tenantId = tenantId,
                entryType = EntryType.DEBIT,
                monetaryEntry = FiatEntry(amount = MonetaryAmount.of("100"), currency = FiatCurrency.USD, rail = PaymentRail.WIRE),
                description = null,
                createdAt = now,
                createdBy = "test",
            )
        val creditLine =
            debitLine.copy(
                id = UUID.randomUUID(),
                accountId = AccountId.generate(),
                entryType = EntryType.CREDIT,
            )
        return Transaction.create(
            id = txId,
            tenantId = tenantId,
            idempotencyKey = "key-1",
            lines = listOf(debitLine, creditLine),
            createdBy = "test",
            occurredAt = now,
            createdAt = now,
        )
    }

    private fun settlement(
        matchedTransactionId: TransactionId? = TransactionId(UUID.randomUUID()),
        confirmedAt: Instant? = now,
    ) = Settlement(
        id = UUID.randomUUID(),
        tenantId = tenantId,
        accountId = AccountId.generate(),
        amount = MonetaryAmount.of("50"),
        token = StablecoinToken.USDC,
        chainId = ChainId.EVM,
        walletAddress = "0xabc",
        status = EntryStatus.SETTLED,
        matchedTransactionId = matchedTransactionId,
        confirmedAt = confirmedAt,
        createdAt = now,
        createdBy = "test",
    )

    @Test
    fun `transactionCommitted creates event with correct fields`() {
        val tx = ledgerTx()
        val event = DomainEvent.transactionCommitted(tx, correlationId)

        assertEquals(DomainEventType.TRANSACTION_COMMITTED, event.eventType)
        assertEquals(tenantId, event.tenantId)
        assertEquals(txId.value, event.referenceId)
        assertEquals(DomainEventReferenceType.TRANSACTION, event.referenceType)
        assertEquals(correlationId, event.correlationId)
        assertEquals(now, event.occurredAt)
        assertNotNull(event.id)
    }

    @Test
    fun `transactionSettled from Transaction creates event with TRANSACTION_SETTLED type`() {
        val tx = ledgerTx()
        val event = DomainEvent.transactionSettled(tx, correlationId)

        assertEquals(DomainEventType.TRANSACTION_SETTLED, event.eventType)
        assertEquals(txId.value, event.referenceId)
        assertEquals(correlationId, event.correlationId)
    }

    @Test
    fun `transactionSettled from Settlement uses matchedTransactionId as referenceId`() {
        val matchedTxId = TransactionId(UUID.randomUUID())
        val event = DomainEvent.transactionSettled(settlement(matchedTransactionId = matchedTxId), correlationId)

        assertEquals(DomainEventType.TRANSACTION_SETTLED, event.eventType)
        assertEquals(matchedTxId.value, event.referenceId)
        assertEquals(DomainEventReferenceType.TRANSACTION, event.referenceType)
    }

    @Test
    fun `transactionSettled from Settlement falls back to Instant now when confirmedAt is null`() {
        val event = DomainEvent.transactionSettled(settlement(confirmedAt = null), correlationId)

        assertNotNull(event.occurredAt)
    }

    @Test
    fun `transactionSettled from Settlement throws when matchedTransactionId is null`() {
        assertFailsWith<IllegalArgumentException> {
            DomainEvent.transactionSettled(settlement(matchedTransactionId = null), correlationId)
        }
    }

    @Test
    fun `reconciliationUnmatched creates event with RECONCILIATION_UNMATCHED type`() {
        val tx = ledgerTx()
        val event = DomainEvent.reconciliationUnmatched(tx, correlationId)

        assertEquals(DomainEventType.RECONCILIATION_UNMATCHED, event.eventType)
        assertEquals(txId.value, event.referenceId)
    }

    @Test
    fun `reconciliationException uses matchedTransactionId as referenceId`() {
        val matchedTxId = TransactionId(UUID.randomUUID())
        val event = DomainEvent.reconciliationException(settlement(matchedTransactionId = matchedTxId), correlationId)

        assertEquals(DomainEventType.RECONCILIATION_EXCEPTION, event.eventType)
        assertEquals(matchedTxId.value, event.referenceId)
    }

    @Test
    fun `reconciliationException falls back to Instant now when confirmedAt is null`() {
        val event = DomainEvent.reconciliationException(settlement(confirmedAt = null), correlationId)

        assertNotNull(event.occurredAt)
    }

    @Test
    fun `reconciliationException throws when matchedTransactionId is null`() {
        assertFailsWith<IllegalArgumentException> {
            DomainEvent.reconciliationException(settlement(matchedTransactionId = null), correlationId)
        }
    }

    @Test
    fun `workflowCommitted creates event with WORKFLOW referenceType`() {
        val plan =
            WorkflowPlan
                .create(
                    id = WorkflowPlanId.generate(),
                    tenantId = tenantId,
                    agentContext = AgentContext(agentId = "a", sessionId = "s"),
                    stepDescriptions = listOf("step-0"),
                    createdAt = now,
                ).copy(completedAt = now)

        val event = DomainEvent.workflowCommitted(plan, correlationId)

        assertEquals(DomainEventType.WORKFLOW_COMMITTED, event.eventType)
        assertEquals(plan.id.value, event.referenceId)
        assertEquals(DomainEventReferenceType.WORKFLOW, event.referenceType)
        assertEquals(now, event.occurredAt)
    }

    @Test
    fun `workflowCommitted throws when WorkflowPlan has null completedAt`() {
        val plan =
            WorkflowPlan.create(
                id = WorkflowPlanId.generate(),
                tenantId = tenantId,
                agentContext = AgentContext(agentId = "a", sessionId = "s"),
                stepDescriptions = listOf("step-0"),
                createdAt = now,
            )
        assertFailsWith<IllegalArgumentException> {
            DomainEvent.workflowCommitted(plan, correlationId)
        }
    }

    @Test
    fun `workflowRolledBack creates event with WORKFLOW referenceType`() {
        val plan =
            WorkflowPlan.create(
                id = WorkflowPlanId.generate(),
                tenantId = tenantId,
                agentContext = AgentContext(agentId = "a", sessionId = "s"),
                stepDescriptions = listOf("step-0"),
                createdAt = now,
            )

        val event = DomainEvent.workflowRolledBack(plan, correlationId)

        assertEquals(DomainEventType.WORKFLOW_ROLLED_BACK, event.eventType)
        assertEquals(plan.id.value, event.referenceId)
        assertEquals(DomainEventReferenceType.WORKFLOW, event.referenceType)
    }

    @Test
    fun `travelRuleRequired creates event with COMPLIANCE_TRAVEL_RULE_REQUIRED type`() {
        val tx = ledgerTx()
        val event = DomainEvent.travelRuleRequired(tx, correlationId)

        assertEquals(DomainEventType.COMPLIANCE_TRAVEL_RULE_REQUIRED, event.eventType)
        assertEquals(txId.value, event.referenceId)
        assertEquals(DomainEventReferenceType.TRANSACTION, event.referenceType)
    }

    @Test
    fun `agentActionFlagged creates event with AGENT_ACTION_FLAGGED type and WORKFLOW referenceType`() {
        val planId = WorkflowPlanId.generate()
        val event = DomainEvent.agentActionFlagged(planId, tenantId, correlationId, now)

        assertEquals(DomainEventType.AGENT_ACTION_FLAGGED, event.eventType)
        assertEquals(planId.value, event.referenceId)
        assertEquals(DomainEventReferenceType.WORKFLOW, event.referenceType)
        assertEquals(tenantId, event.tenantId)
        assertEquals(now, event.occurredAt)
    }
}
