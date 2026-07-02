package finance.idem.infrastructure.chain

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.aot.hint.RuntimeHints

class ChainWebhookRuntimeHintsTest {
    @Test
    fun `registers reflection hints for every Alchemy and QuickNode payload type`() {
        val hints = RuntimeHints()

        ChainWebhookRuntimeHints().registerHints(hints, javaClass.classLoader)

        listOf(
            AlchemyWebhookPayload::class.java,
            AlchemyWebhookEvent::class.java,
            AlchemyActivity::class.java,
            AlchemyRawContract::class.java,
            AlchemyLog::class.java,
            QuickNodeWebhookPayload::class.java,
            QuickNodeStreamPayload::class.java,
            QuickNodeStreamMetadata::class.java,
        ).forEach { type ->
            assertNotNull(hints.reflection().getTypeHint(type), "expected a reflection hint for ${type.name}")
        }
    }
}
