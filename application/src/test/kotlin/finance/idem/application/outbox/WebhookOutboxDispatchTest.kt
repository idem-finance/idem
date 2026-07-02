package finance.idem.application.outbox

import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class WebhookOutboxDispatchTest {
    @Test
    fun `WebhookOutboxDispatch holds all fields`() {
        val id = UUID.randomUUID()
        val tenantId = TenantId.generate()

        val dispatch =
            WebhookOutboxDispatch(
                id = id,
                tenantId = tenantId,
                eventType = "transaction.committed",
                payload = """{"eventType":"transaction.committed"}""",
                attempts = 2,
            )

        assertEquals(id, dispatch.id)
        assertEquals(tenantId, dispatch.tenantId)
        assertEquals("transaction.committed", dispatch.eventType)
        assertEquals("""{"eventType":"transaction.committed"}""", dispatch.payload)
        assertEquals(2, dispatch.attempts)
        assertEquals(dispatch, dispatch.copy())
    }
}
