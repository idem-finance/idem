package finance.idem.core.agentic

import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.WorkflowPlanId
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowPlanTest {

    private val tenantId = TenantId.generate()
    private val planId = WorkflowPlanId.generate()
    private val agentContext = AgentContext(agentId = "agent-1", sessionId = "sess-abc")
    private val now = Instant.now()

    @Test
    fun `create initializes with PLANNED status and PENDING steps`() {
        val plan = WorkflowPlan.create(
            id = planId,
            tenantId = tenantId,
            agentContext = agentContext,
            stepIdempotencyKeys = listOf("step-0-key", "step-1-key"),
            occurredAt = now,
        )

        assertEquals(WorkflowPlanStatus.PLANNED, plan.status)
        assertEquals(2, plan.steps.size)
        plan.steps.forEach { step ->
            assertEquals(WorkflowStepStatus.PENDING, step.status)
            assertNull(step.transactionId)
        }
        assertEquals(0, plan.steps[0].stepIndex)
        assertEquals("step-0-key", plan.steps[0].idempotencyKey)
        assertEquals(1, plan.steps[1].stepIndex)
        assertEquals("step-1-key", plan.steps[1].idempotencyKey)
        assertNull(plan.committedAt)
    }

    @Test
    fun `withStatus returns new plan with updated status`() {
        val plan = planWithSteps()

        val executing = plan.withStatus(WorkflowPlanStatus.EXECUTING)
        assertEquals(WorkflowPlanStatus.EXECUTING, executing.status)

        val committed = executing.withStatus(WorkflowPlanStatus.COMMITTED)
        assertEquals(WorkflowPlanStatus.COMMITTED, committed.status)
    }

    @Test
    fun `withStepExecuted marks step EXECUTED and records transactionId`() {
        val plan = planWithSteps()
        val txId = TransactionId.generate()

        val updated = plan.withStepExecuted(stepIndex = 0, txId = txId)

        assertEquals(WorkflowStepStatus.EXECUTED, updated.steps[0].status)
        assertEquals(txId, updated.steps[0].transactionId)
        assertEquals(WorkflowStepStatus.PENDING, updated.steps[1].status)
    }

    @Test
    fun `withStepFailed marks only the target step FAILED`() {
        val plan = planWithSteps()

        val updated = plan.withStepFailed(stepIndex = 1)

        assertEquals(WorkflowStepStatus.PENDING, updated.steps[0].status)
        assertEquals(WorkflowStepStatus.FAILED, updated.steps[1].status)
    }

    @Test
    fun `executedSteps returns only EXECUTED steps`() {
        val txId = TransactionId.generate()
        val plan = planWithSteps()
            .withStepExecuted(stepIndex = 0, txId = txId)
            .withStepFailed(stepIndex = 1)

        val executed = plan.executedSteps()

        assertEquals(1, executed.size)
        assertEquals(0, executed[0].stepIndex)
        assertEquals(txId, executed[0].transactionId)
    }

    @Test
    fun `executedSteps returns empty list when no steps executed`() {
        val plan = planWithSteps()
        assertTrue(plan.executedSteps().isEmpty())
    }

    @Test
    fun `reconstitute preserves all fields`() {
        val committedAt = Instant.now()
        val steps = listOf(
            WorkflowPlanStep(0, "key-0", WorkflowStepStatus.EXECUTED, TransactionId.generate()),
            WorkflowPlanStep(1, "key-1", WorkflowStepStatus.PENDING, null),
        )

        val plan = WorkflowPlan.reconstitute(
            id = planId,
            tenantId = tenantId,
            agentContext = agentContext,
            status = WorkflowPlanStatus.COMMITTED,
            steps = steps,
            occurredAt = now,
            committedAt = committedAt,
        )

        assertEquals(planId, plan.id)
        assertEquals(tenantId, plan.tenantId)
        assertEquals(WorkflowPlanStatus.COMMITTED, plan.status)
        assertEquals(2, plan.steps.size)
        assertNotNull(plan.committedAt)
    }

    private fun planWithSteps() = WorkflowPlan.create(
        id = planId,
        tenantId = tenantId,
        agentContext = agentContext,
        stepIdempotencyKeys = listOf("key-0", "key-1"),
        occurredAt = now,
    )
}
