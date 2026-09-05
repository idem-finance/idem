package finance.idem.core.events

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DomainEventTypeTest {
    @Test
    fun `every DomainEventType value round-trips through valueOf`() {
        DomainEventType.values().forEach { value ->
            assertEquals(value, DomainEventType.valueOf(value.name))
        }
    }

    @Test
    fun `every DomainEventReferenceType value round-trips through valueOf`() {
        DomainEventReferenceType.values().forEach { value ->
            assertEquals(value, DomainEventReferenceType.valueOf(value.name))
        }
    }
}
