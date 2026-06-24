package finance.idem.application.reconciliation

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class ReconcileEntriesResultTest {

    @Test
    fun `preserves all fields`() {
        val ex = ReconciliationException(UUID.randomUUID(), "0xabc", "No match")
        val result = ReconcileEntriesResult(
            matched = 3,
            unmatched = 1,
            exceptions = listOf(ex),
            settlementIds = listOf("id-1", "id-2"),
        )

        assertEquals(3, result.matched)
        assertEquals(1, result.unmatched)
        assertEquals(listOf(ex), result.exceptions)
        assertEquals(listOf("id-1", "id-2"), result.settlementIds)
    }

    @Test
    fun `settlementIds defaults to empty list`() {
        val result = ReconcileEntriesResult(matched = 0, unmatched = 0, exceptions = emptyList())
        assertEquals(emptyList(), result.settlementIds)
    }

    @Test
    fun `copy produces independent instance`() {
        val original = ReconcileEntriesResult(matched = 1, unmatched = 0, exceptions = emptyList())
        val copy = original.copy(matched = 5)

        assertEquals(1, original.matched)
        assertEquals(5, copy.matched)
    }

    @Test
    fun `ReconciliationException preserves all fields including null txHash`() {
        val id = UUID.randomUUID()
        val ex = ReconciliationException(settlementId = id, txHash = null, reason = "reason")

        assertEquals(id, ex.settlementId)
        assertEquals(null, ex.txHash)
        assertEquals("reason", ex.reason)
    }
}
