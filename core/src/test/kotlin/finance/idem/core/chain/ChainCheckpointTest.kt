package finance.idem.core.chain

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ChainCheckpointTest {

    @Test
    fun `equality is based on all fields`() {
        val now = Instant.now()
        val a = ChainCheckpoint("EVM_1", 19_000_000L, now)
        val b = ChainCheckpoint("EVM_1", 19_000_000L, now)
        assertEquals(a, b)
    }

    @Test
    fun `different chainKeys are not equal`() {
        val now = Instant.now()
        assertNotEquals(
            ChainCheckpoint("EVM_1", 100L, now),
            ChainCheckpoint("EVM_8453", 100L, now),
        )
    }

    @Test
    fun `copy with updated lastBlock reflects new value`() {
        val original = ChainCheckpoint("SOLANA", 250_000_000L, Instant.now())
        val updated = original.copy(lastBlock = 250_001_000L)
        assertEquals("SOLANA", updated.chainKey)
        assertEquals(250_001_000L, updated.lastBlock)
    }
}
