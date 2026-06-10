package finance.idem.core.ledger

import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettlementTest {

    private val now = Instant.now()
    private val tenantId = TenantId.generate()
    private val accountId = AccountId.generate()
    private val watchedWallet = "5FHwkrdxkTEBqVTBmRjfBknDiCMWB6cYPQCGt1tnk9HS"

    private fun pendingSettlement(id: UUID = UUID.randomUUID()) = Settlement(
        id = id,
        tenantId = tenantId,
        accountId = accountId,
        amount = MonetaryAmount.of("100.000000"),
        token = StablecoinToken.USDC,
        chainId = ChainId.SOLANA,
        walletAddress = watchedWallet,
        status = EntryStatus.PENDING,
        createdAt = now,
        createdBy = "api-user",
    )

    @Test
    fun `EntryStatus contains all four lifecycle values`() {
        val values = EntryStatus.entries
        assertEquals(4, values.size)
        assertTrue(EntryStatus.PENDING in values)
        assertTrue(EntryStatus.SETTLED in values)
        assertTrue(EntryStatus.UNMATCHED in values)
        assertTrue(EntryStatus.CANCELLED in values)
    }

    @Test
    fun `PENDING settlement defaults proof fields to null`() {
        val settlement = pendingSettlement()

        assertEquals(EntryStatus.PENDING, settlement.status)
        assertNull(settlement.matchedTransactionId)
        assertNull(settlement.txHash)
        assertNull(settlement.blockNumber)
        assertNull(settlement.confirmedAt)
    }

    @Test
    fun `SETTLED settlement carries on-chain proof fields`() {
        val matchedTransactionId = TransactionId.generate()
        val confirmedAt = Instant.now()

        val settlement = pendingSettlement().copy(
            status = EntryStatus.SETTLED,
            matchedTransactionId = matchedTransactionId,
            txHash = "5j7s6XxnkqxAbcDE1234567890abcdefghijklmnopqrstuvwxyz1234567",
            blockNumber = 250_000_000L,
            confirmedAt = confirmedAt,
        )

        assertEquals(EntryStatus.SETTLED, settlement.status)
        assertEquals(matchedTransactionId, settlement.matchedTransactionId)
        assertEquals("5j7s6XxnkqxAbcDE1234567890abcdefghijklmnopqrstuvwxyz1234567", settlement.txHash)
        assertEquals(250_000_000L, settlement.blockNumber)
        assertNotNull(settlement.confirmedAt)
    }

    @Test
    fun `equality is structural`() {
        val id = UUID.randomUUID()
        assertEquals(pendingSettlement(id), pendingSettlement(id))
    }

    @Test
    fun `copy transitions status while preserving id`() {
        val original = pendingSettlement()

        val updated = original.copy(status = EntryStatus.UNMATCHED)

        assertEquals(original.id, updated.id)
        assertEquals(EntryStatus.UNMATCHED, updated.status)
        assertNotEquals(original, updated)
    }
}
