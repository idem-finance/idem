package finance.idem.api.ledger

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.aot.hint.RuntimeHints

class MonetaryEntryDtoRuntimeHintsTest {
    @Test
    fun `registers reflection hints for every polymorphic MonetaryEntry DTO`() {
        val hints = RuntimeHints()

        MonetaryEntryDtoRuntimeHints().registerHints(hints, javaClass.classLoader)

        listOf(
            MonetaryEntryRequestDto::class.java,
            FiatEntryDto::class.java,
            OnChainEntryDto::class.java,
            MonetaryEntryResponse::class.java,
            FiatEntryResponse::class.java,
            OnChainEntryResponse::class.java,
        ).forEach { type ->
            assertNotNull(hints.reflection().getTypeHint(type), "expected a reflection hint for ${type.name}")
        }
    }
}
