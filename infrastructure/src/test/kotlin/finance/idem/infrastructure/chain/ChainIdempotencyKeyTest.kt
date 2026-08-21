package finance.idem.infrastructure.chain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ChainIdempotencyKeyTest {
    @Test
    fun `of joins chainKey, txHash, and logIndex with colons`() {
        assertEquals("EVM_1:0xabc:2", ChainIdempotencyKey.of("EVM_1", "0xabc", 2))
    }

    @Test
    fun `of matches the format EvmChainReader and AlchemyWebhookService rely on for release`() {
        assertEquals("EVM_8453:0xdef456:0", ChainIdempotencyKey.of("EVM_8453", "0xdef456", 0))
    }
}
