package finance.idem.core.agentic

import finance.idem.core.TenantId
import finance.idem.core.WorkflowPlanId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AgentAuditEventTest {

    private val workflowPlanId = WorkflowPlanId.generate()
    private val tenantId = TenantId.generate()
    private val agentContext = AgentContext(
        agentId = "agent-1",
        sessionId = "sess-abc",
        workflowPlanId = workflowPlanId,
        intent = "offramp",
    )

    @Test
    fun `pending creates event with PENDING status and no outcome`() {
        val event = AgentAuditEvent.pending(
            workflowPlanId = workflowPlanId,
            tenantId = tenantId,
            agentContext = agentContext,
            intent = "offramp",
        )

        assertEquals(AgentAuditStatus.PENDING, event.status)
        assertEquals(workflowPlanId, event.workflowPlanId)
        assertEquals(tenantId, event.tenantId)
        assertEquals("offramp", event.intent)
        assertNull(event.outcome)
        assertNotNull(event.id)
        assertNotNull(event.occurredAt)
    }

    @Test
    fun `pending with null intent stores null`() {
        val event = AgentAuditEvent.pending(
            workflowPlanId = workflowPlanId,
            tenantId = tenantId,
            agentContext = agentContext,
            intent = null,
        )
        assertNull(event.intent)
    }

    @Test
    fun `completed creates event with COMPLETED status and outcome`() {
        val event = AgentAuditEvent.completed(
            workflowPlanId = workflowPlanId,
            tenantId = tenantId,
            agentContext = agentContext,
            outcome = "Workflow committed with 2 step(s)",
        )

        assertEquals(AgentAuditStatus.COMPLETED, event.status)
        assertEquals("Workflow committed with 2 step(s)", event.outcome)
        assertEquals(agentContext.intent, event.intent)
    }

    @Test
    fun `failed creates event with FAILED status and outcome`() {
        val event = AgentAuditEvent.failed(
            workflowPlanId = workflowPlanId,
            tenantId = tenantId,
            agentContext = agentContext,
            outcome = "Step 1 failed: account not found",
        )

        assertEquals(AgentAuditStatus.FAILED, event.status)
        assertEquals("Step 1 failed: account not found", event.outcome)
    }

    @Test
    fun `each factory call generates a unique id`() {
        val e1 = AgentAuditEvent.pending(workflowPlanId, tenantId, agentContext, null)
        val e2 = AgentAuditEvent.pending(workflowPlanId, tenantId, agentContext, null)
        assert(e1.id != e2.id)
    }
}
