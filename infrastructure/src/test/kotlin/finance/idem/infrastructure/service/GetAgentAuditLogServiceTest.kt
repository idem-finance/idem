package finance.idem.infrastructure.service

import finance.idem.application.agentic.GetAgentAuditLogQuery
import finance.idem.application.port.AgentAuditRepository
import finance.idem.application.port.AgentAuditView
import finance.idem.core.TenantId
import finance.idem.core.agentic.AgentAuditEvent
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class GetAgentAuditLogServiceTest {

    private val tenantId = TenantId.generate()

    // TenantId is @JvmInline — findByFilter's JVM signature is mangled; use a concrete anon object.
    private var stubbedResult: List<AgentAuditView> = emptyList()
    private var capturedSessionId: String? = null
    private var capturedLimit: Int = -1

    private val repo: AgentAuditRepository = object : AgentAuditRepository {
        override fun save(event: AgentAuditEvent) = Unit
        override fun findByFilter(
            tenantId: TenantId,
            sessionId: String?,
            from: Instant?,
            to: Instant?,
            limit: Int,
        ): List<AgentAuditView> {
            capturedSessionId = sessionId
            capturedLimit = limit
            return stubbedResult
        }
    }

    private val service = GetAgentAuditLogService(repo)

    @Test
    fun `delegates to repository with query fields`() {
        val now = Instant.now()
        service.execute(GetAgentAuditLogQuery(
            tenantId = tenantId,
            sessionId = "sess-1",
            from = now.minusSeconds(3600),
            to = now,
            limit = 25,
        ))
        assertEquals("sess-1", capturedSessionId)
        assertEquals(25, capturedLimit)
    }

    @Test
    fun `returns repository results`() {
        val view = AgentAuditView(
            id = UUID.randomUUID(), workflowPlanId = UUID.randomUUID(),
            agentId = "a", sessionId = "s", eventType = "AGENT_ACTION_STARTED",
            intentPayload = null, status = "PENDING", occurredAt = Instant.now(),
            completedAt = null, hmacSignature = "sig",
        )
        stubbedResult = listOf(view)
        val result = service.execute(GetAgentAuditLogQuery(tenantId = tenantId))
        assertEquals(1, result.size)
        assertEquals(view.id, result[0].id)
    }

    @Test
    fun `clamps limit to maximum 200`() {
        service.execute(GetAgentAuditLogQuery(tenantId = tenantId, limit = 999))
        assertEquals(200, capturedLimit)
    }

    @Test
    fun `clamps limit to minimum 1`() {
        service.execute(GetAgentAuditLogQuery(tenantId = tenantId, limit = 0))
        assertEquals(1, capturedLimit)
    }
}
