package finance.idem.application.outbox

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutboxStatusTest {

    @Test
    fun `OutboxStatus contains the expected retry state machine values`() {
        val values = OutboxStatus.entries
        assertEquals(4, values.size)
        assertTrue(OutboxStatus.PENDING in values)
        assertTrue(OutboxStatus.DELIVERED in values)
        assertTrue(OutboxStatus.FAILED in values)
        assertTrue(OutboxStatus.DEAD in values)
    }

    @Test
    fun `OutboxStatus values round-trip through valueOf`() {
        OutboxStatus.entries.forEach { status ->
            assertEquals(status, OutboxStatus.valueOf(status.name))
        }
    }
}
