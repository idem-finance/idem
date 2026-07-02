package finance.idem.application.agentic

import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GetAgentAuditLogModelsTest {
    private val tenantId = TenantId.generate()
    private val now = Instant.now()

    @Test
    fun `GetAgentAuditLogQuery holds all fields`() {
        val query =
            GetAgentAuditLogQuery(
                tenantId = tenantId,
                sessionId = "sess-1",
                from = now.minusSeconds(3600),
                to = now,
                limit = 25,
            )
        assertEquals(tenantId, query.tenantId)
        assertEquals("sess-1", query.sessionId)
        assertEquals(now.minusSeconds(3600), query.from)
        assertEquals(now, query.to)
        assertEquals(25, query.limit)
    }

    @Test
    fun `GetAgentAuditLogQuery optional fields default to null and limit defaults to 50`() {
        val query = GetAgentAuditLogQuery(tenantId = tenantId)
        assertNull(query.sessionId)
        assertNull(query.from)
        assertNull(query.to)
        assertEquals(50, query.limit)
    }
}
