package finance.idem.application.agentic

import finance.idem.application.ledger.JournalLineRequest
import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.AgentContext
import finance.idem.core.agentic.WorkflowPlan
import finance.idem.core.monetary.FiatEntry
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WorkflowModelsTest {
    private val tenantId = TenantId.generate()
    private val planId = WorkflowPlanId.generate()
    private val agentContext = AgentContext(agentId = "agent-1", sessionId = "sess-abc")
    private val now = Instant.now()

    private fun brlLine(
        accountId: AccountId,
        type: EntryType,
    ) = JournalLineRequest(
        accountId = accountId,
        entryType = type,
        monetaryEntry = FiatEntry(MonetaryAmount.of("100"), FiatCurrency.BRL, PaymentRail.PIX),
    )

    private fun committedPlan() =
        WorkflowPlan
            .create(
                id = planId,
                tenantId = tenantId,
                agentContext = agentContext,
                stepDescriptions = listOf("step-0"),
                createdAt = now,
            ).copy(completedAt = Instant.now())

    // ── WorkflowStepCommand ───────────────────────────────────────────────────

    @Test
    fun `WorkflowStepCommand holds idempotencyKey and lines`() {
        val line = brlLine(AccountId.generate(), EntryType.DEBIT)
        val cmd = WorkflowStepCommand(idempotencyKey = "idem-step-0", lines = listOf(line))

        assertEquals("idem-step-0", cmd.idempotencyKey)
        assertEquals(1, cmd.lines.size)
        assertEquals(emptyMap(), cmd.metadata)
    }

    @Test
    fun `WorkflowStepCommand accepts metadata`() {
        val cmd =
            WorkflowStepCommand(
                idempotencyKey = "k",
                lines = emptyList(),
                metadata = mapOf("ref" to "abc"),
            )
        assertEquals(mapOf("ref" to "abc"), cmd.metadata)
    }

    @Test
    fun `WorkflowStepCommand description defaults to empty string`() {
        val cmd = WorkflowStepCommand(idempotencyKey = "k", lines = emptyList())
        assertEquals("", cmd.description)
    }

    // ── ExecuteWorkflowCommand ────────────────────────────────────────────────

    @Test
    fun `ExecuteWorkflowCommand holds all fields`() {
        val step = WorkflowStepCommand("idem-0", lines = emptyList())
        val cmd =
            ExecuteWorkflowCommand(
                tenantId = tenantId,
                agentContext = agentContext,
                steps = listOf(step),
                createdBy = "sk_agent_test",
            )

        assertEquals(tenantId, cmd.tenantId)
        assertEquals(agentContext, cmd.agentContext)
        assertEquals(1, cmd.steps.size)
        assertEquals("sk_agent_test", cmd.createdBy)
    }

    // ── RollbackWorkflowCommand ───────────────────────────────────────────────

    @Test
    fun `RollbackWorkflowCommand holds all fields`() {
        val cmd =
            RollbackWorkflowCommand(
                tenantId = tenantId,
                agentContext = agentContext,
                workflowPlanId = planId,
                reason = "compliance review",
                createdBy = "sk_agent_test",
            )

        assertEquals(tenantId, cmd.tenantId)
        assertEquals(planId, cmd.workflowPlanId)
        assertEquals("compliance review", cmd.reason)
    }

    // ── WorkflowError ─────────────────────────────────────────────────────────

    @Test
    fun `WorkflowPlanNotFound carries planId and message`() {
        val error = WorkflowPlanNotFound(planId)
        assertEquals(planId, error.workflowPlanId)
        assertIs<WorkflowError>(error)
        assertNotNull(error.message)
    }

    // ── RollbackWorkflowSummary ───────────────────────────────────────────────

    @Test
    fun `RollbackWorkflowSummary holds all fields`() {
        val txId = TransactionId(UUID.randomUUID())
        val step = CompensatedStepSummary(stepOrder = 0, description = "Transfer", compensatingTransactionId = txId)
        val summary =
            RollbackWorkflowSummary(
                workflowPlanId = planId,
                compensatedSteps = listOf(step),
                status = "ROLLED_BACK",
            )

        assertEquals(planId, summary.workflowPlanId)
        assertEquals("ROLLED_BACK", summary.status)
        assertEquals(1, summary.compensatedSteps.size)
        assertEquals(0, summary.compensatedSteps[0].stepOrder)
        assertEquals("Transfer", summary.compensatedSteps[0].description)
        assertEquals(txId, summary.compensatedSteps[0].compensatingTransactionId)
    }

    @Test
    fun `CompensatedStepSummary allows null compensatingTransactionId`() {
        val step = CompensatedStepSummary(stepOrder = 1, description = "Rollback step", compensatingTransactionId = null)

        assertEquals(1, step.stepOrder)
        assertNull(step.compensatingTransactionId)
    }

    // ── WebhookOutboxEntry workflow factory methods ───────────────────────────

    @Test
    fun `workflowCommitted creates entry with correct eventType and planId as transactionId`() {
        val plan = committedPlan()
        val entry = WebhookOutboxEntry.workflowCommitted(plan)

        assertEquals("workflow.committed", entry.eventType)
        assertEquals(plan.tenantId, entry.tenantId)
        assertEquals(plan.id.value, entry.transactionId.value)
        assertNotNull(entry.occurredAt)
    }

    @Test
    fun `workflowRolledBack creates entry with correct eventType`() {
        val plan = committedPlan()
        val entry = WebhookOutboxEntry.workflowRolledBack(plan)

        assertEquals("workflow.rolled_back", entry.eventType)
        assertEquals(plan.tenantId, entry.tenantId)
        assertEquals(plan.id.value, entry.transactionId.value)
    }
}
