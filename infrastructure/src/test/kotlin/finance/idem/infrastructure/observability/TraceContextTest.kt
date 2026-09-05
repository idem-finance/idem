package finance.idem.infrastructure.observability

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TraceContextTest {
    @AfterEach
    fun tearDown() {
        MDC.clear()
    }

    @Test
    fun `currentOrNew returns the bound MDC trace id when present`() {
        MDC.put(TraceIdFilter.MDC_KEY, "bound-trace-id")

        assertEquals("bound-trace-id", TraceContext.currentOrNew())
    }

    @Test
    fun `currentOrNew returns a fresh UUID when no trace id is bound`() {
        val correlationId = TraceContext.currentOrNew()

        assertTrue(runCatching { UUID.fromString(correlationId) }.isSuccess)
    }

    @Test
    fun `currentOrNew generates a different value on each call when nothing is bound`() {
        assertNotEquals(TraceContext.currentOrNew(), TraceContext.currentOrNew())
    }
}
