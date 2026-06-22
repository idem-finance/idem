package finance.idem.application.reconciliation

import finance.idem.core.AccountId
import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReconcileEntriesResultTest {

    @Test
    fun `preserves all fields`() {
        val ex = ReconciliationException(UUID.randomUUID(), "0xabc", "No match")
        val result = ReconcileEntriesResult(matched = 3, unmatched = 1, exceptions = listOf(ex))

        assertEquals(3, result.matched)
        assertEquals(1, result.unmatched)
        assertEquals(listOf(ex), result.exceptions)
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

    @Test
    fun `ReconcileEntriesCommand preserves all fields`() {
        val tenantId = TenantId.generate()
        val accountId = AccountId.generate()
        val from = Instant.now().minusSeconds(3600)
        val to = Instant.now()
        val cmd = ReconcileEntriesCommand(tenantId = tenantId, accountId = accountId, from = from, to = to)

        assertEquals(tenantId, cmd.tenantId)
        assertEquals(accountId, cmd.accountId)
        assertEquals(from, cmd.from)
        assertEquals(to, cmd.to)
    }

    @Test
    fun `ReconcileEntriesCommand defaults accountId to null`() {
        val cmd = ReconcileEntriesCommand(
            tenantId = TenantId.generate(),
            from = Instant.now().minusSeconds(3600),
            to = Instant.now(),
        )
        assertNull(cmd.accountId)
    }
}
