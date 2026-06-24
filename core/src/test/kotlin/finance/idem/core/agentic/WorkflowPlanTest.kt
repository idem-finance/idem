package finance.idem.core.agentic

import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.WorkflowPlanId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
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
            stepDescriptions = listOf("Transfer funds", "Settle on-chain"),
            createdAt = now,
        )

        assertEquals(WorkflowStatus.PLANNED, plan.status)
        assertEquals(2, plan.steps.size)
        plan.steps.forEach { step ->
            assertEquals(StepStatus.PENDING, step.status)
            assertNull(step.transactionId)
            assertNull(step.executedAt)
            assertNull(step.compensatingTransactionId)
        }
        assertEquals(0, plan.steps[0].stepOrder)
        assertEquals("Transfer funds", plan.steps[0].description)
        assertEquals(1, plan.steps[1].stepOrder)
        assertEquals("Settle on-chain", plan.steps[1].description)
        assertNull(plan.completedAt)
        assertNull(plan.rolledBackAt)
        assertNull(plan.rollbackReason)
    }

    @Test
    fun `create assigns unique stepId to each step`() {
        val plan = planWithSteps()
        val ids = plan.steps.map { it.stepId }.toSet()
        assertEquals(2, ids.size, "Each step must have a distinct stepId")
    }

    @Test
    fun `withStatus returns new plan with updated status, leaves steps unchanged`() {
        val plan = planWithSteps()

        val executing = plan.withStatus(WorkflowStatus.EXECUTING)
        assertEquals(WorkflowStatus.EXECUTING, executing.status)
        assertEquals(plan.steps, executing.steps)

        val committed = executing.withStatus(WorkflowStatus.COMMITTED)
        assertEquals(WorkflowStatus.COMMITTED, committed.status)
    }

    @Test
    fun `withStepExecuted marks step EXECUTED and records transactionId and executedAt`() {
        val plan = planWithSteps()
        val txId = TransactionId.generate()

        val updated = plan.withStepExecuted(stepOrder = 0, txId = txId)

        assertEquals(StepStatus.EXECUTED, updated.steps[0].status)
        assertEquals(txId, updated.steps[0].transactionId)
        assertNotNull(updated.steps[0].executedAt)
        assertEquals(StepStatus.PENDING, updated.steps[1].status)
    }

    @Test
    fun `withStepFailed marks only the target step FAILED, leaves executedAt null`() {
        val plan = planWithSteps()

        val updated = plan.withStepFailed(stepOrder = 1)

        assertEquals(StepStatus.PENDING, updated.steps[0].status)
        assertEquals(StepStatus.FAILED, updated.steps[1].status)
        assertNull(updated.steps[1].executedAt)
    }

    @Test
    fun `withStepRolledBack marks step ROLLED_BACK and records compensatingTransactionId`() {
        val txId = TransactionId.generate()
        val compensatingTxId = TransactionId.generate()

        val plan = planWithSteps()
            .withStepExecuted(0, txId)
            .withStepRolledBack(0, compensatingTxId)

        assertEquals(StepStatus.ROLLED_BACK, plan.steps[0].status)
        assertEquals(compensatingTxId, plan.steps[0].compensatingTransactionId)
        assertEquals(txId, plan.steps[0].transactionId)
    }

    @Test
    fun `executedSteps returns only EXECUTED steps`() {
        val txId = TransactionId.generate()
        val plan = planWithSteps()
            .withStepExecuted(stepOrder = 0, txId = txId)
            .withStepFailed(stepOrder = 1)

        val executed = plan.executedSteps()

        assertEquals(1, executed.size)
        assertEquals(0, executed[0].stepOrder)
        assertEquals(txId, executed[0].transactionId)
    }

    @Test
    fun `executedSteps returns empty list when no steps executed`() {
        val plan = planWithSteps()
        assertTrue(plan.executedSteps().isEmpty())
    }

    @Test
    fun `executedSteps excludes ROLLED_BACK steps`() {
        val tx0 = TransactionId.generate()
        val tx1 = TransactionId.generate()
        val compensating = TransactionId.generate()

        val plan = planWithSteps()
            .withStepExecuted(0, tx0)
            .withStepExecuted(1, tx1)
            .withStepRolledBack(0, compensating)

        // Only step 1 is still EXECUTED; step 0 was rolled back
        val executed = plan.executedSteps()
        assertEquals(1, executed.size)
        assertEquals(1, executed[0].stepOrder)
    }

    @Test
    fun `reconstitute preserves all fields including new ones`() {
        val completedAt = Instant.now()
        val rolledBackAt = Instant.now()
        val stepId = UUID.randomUUID()
        val steps = listOf(
            WorkflowStep(stepId, 0, "Transfer", TransactionId.generate(), StepStatus.ROLLED_BACK, completedAt, TransactionId.generate()),
            WorkflowStep(UUID.randomUUID(), 1, "Settle", null, StepStatus.PENDING, null, null),
        )

        val plan = WorkflowPlan.reconstitute(
            id = planId,
            tenantId = tenantId,
            agentContext = agentContext,
            status = WorkflowStatus.ROLLED_BACK,
            steps = steps,
            createdAt = now,
            completedAt = completedAt,
            rolledBackAt = rolledBackAt,
            rollbackReason = "compliance",
        )

        assertEquals(planId, plan.id)
        assertEquals(tenantId, plan.tenantId)
        assertEquals(WorkflowStatus.ROLLED_BACK, plan.status)
        assertEquals(2, plan.steps.size)
        assertNotNull(plan.completedAt)
        assertNotNull(plan.rolledBackAt)
        assertEquals("compliance", plan.rollbackReason)
        assertEquals(stepId, plan.steps[0].stepId)
        assertEquals(StepStatus.ROLLED_BACK, plan.steps[0].status)
    }

    private fun planWithSteps() = WorkflowPlan.create(
        id = planId,
        tenantId = tenantId,
        agentContext = agentContext,
        stepDescriptions = listOf("step-0", "step-1"),
        createdAt = now,
    )
}
