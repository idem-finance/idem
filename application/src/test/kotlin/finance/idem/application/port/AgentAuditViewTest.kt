package finance.idem.application.port

import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentAuditViewTest {
    @Test
    fun `preserves all fields`() {
        val id = UUID.randomUUID()
        val planId = UUID.randomUUID()
        val now = Instant.now()
        val view =
            AgentAuditView(
                id = id,
                workflowPlanId = planId,
                agentId = "agent-1",
                sessionId = "sess-abc",
                eventType = "AGENT_ACTION_COMPLETED",
                intentPayload = "transfer",
                status = "COMPLETED",
                occurredAt = now,
                completedAt = now,
                hmacSignature = "sig",
            )

        assertEquals(id, view.id)
        assertEquals(planId, view.workflowPlanId)
        assertEquals("agent-1", view.agentId)
        assertEquals("sess-abc", view.sessionId)
        assertEquals("AGENT_ACTION_COMPLETED", view.eventType)
        assertEquals("transfer", view.intentPayload)
        assertEquals("COMPLETED", view.status)
        assertEquals(now, view.occurredAt)
        assertEquals(now, view.completedAt)
        assertEquals("sig", view.hmacSignature)
    }

    @Test
    fun `completedAt and intentPayload can be null`() {
        val view =
            AgentAuditView(
                id = UUID.randomUUID(),
                workflowPlanId = UUID.randomUUID(),
                agentId = "agent-1",
                sessionId = "sess-abc",
                eventType = "AGENT_ACTION_STARTED",
                intentPayload = null,
                status = "PENDING",
                occurredAt = Instant.now(),
                completedAt = null,
                hmacSignature = "sig",
            )

        assertNull(view.completedAt)
        assertNull(view.intentPayload)
    }
}
