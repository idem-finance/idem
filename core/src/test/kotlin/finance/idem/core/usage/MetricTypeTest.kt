package finance.idem.core.usage

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MetricTypeTest {
    @Test
    fun `entries contains exactly the five metric types`() {
        assertEquals(
            setOf(
                MetricType.TRANSACTION_COUNT,
                MetricType.API_CALL_COUNT,
                MetricType.CHAIN_EVENT_COUNT,
                MetricType.WEBHOOK_DELIVERY_COUNT,
                MetricType.ENTRY_COUNT,
            ),
            MetricType.entries.toSet(),
        )
    }

    @Test
    fun `valueOf round-trips each entry's name`() {
        MetricType.entries.forEach { assertEquals(it, MetricType.valueOf(it.name)) }
    }
}
