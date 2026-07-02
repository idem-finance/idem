package finance.idem.core.agentic

import finance.idem.core.WorkflowPlanId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentContextTest {
    @Test
    fun `constructs with required fields`() {
        val ctx = AgentContext(agentId = "agent-1", sessionId = "sess-abc")
        assertEquals("agent-1", ctx.agentId)
        assertEquals("sess-abc", ctx.sessionId)
        assertNull(ctx.workflowPlanId)
        assertNull(ctx.intent)
    }

    @Test
    fun `constructs with all optional fields`() {
        val planId = WorkflowPlanId.generate()
        val ctx =
            AgentContext(
                agentId = "agent-2",
                sessionId = "sess-xyz",
                workflowPlanId = planId,
                intent = "post_offramp_transaction",
            )
        assertEquals(planId, ctx.workflowPlanId)
        assertEquals("post_offramp_transaction", ctx.intent)
    }
}
