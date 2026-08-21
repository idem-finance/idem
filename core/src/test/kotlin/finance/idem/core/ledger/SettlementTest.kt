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

    private fun pendingSettlement(id: UUID = UUID.randomUUID()) =
        Settlement(
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
    fun `EntryStatus contains all six lifecycle values`() {
        val values = EntryStatus.entries
        assertEquals(6, values.size)
        assertTrue(EntryStatus.PENDING in values)
        assertTrue(EntryStatus.WATCHING in values)
        assertTrue(EntryStatus.SETTLED in values)
        assertTrue(EntryStatus.UNMATCHED in values)
        assertTrue(EntryStatus.CANCELLED in values)
        assertTrue(EntryStatus.REORGED in values)
    }

    @Test
    fun `PENDING settlement defaults proof fields to null`() {
        val settlement = pendingSettlement()

        assertEquals(EntryStatus.PENDING, settlement.status)
        assertNull(settlement.matchedTransactionId)
        assertNull(settlement.txHash)
        assertNull(settlement.blockNumber)
        assertNull(settlement.confirmedAt)
        assertNull(settlement.chainKey)
        assertNull(settlement.logIndex)
        assertNull(settlement.observedBlockHeight)
        assertNull(settlement.confirmationSource)
        assertNull(settlement.confirmationsRequired)
        assertNull(settlement.reversalTransactionId)
        assertNull(settlement.reorgedAt)
    }

    @Test
    fun `WATCHING settlement carries finality evidence without confirmedAt`() {
        val matchedTransactionId = TransactionId.generate()

        val settlement =
            pendingSettlement().copy(
                status = EntryStatus.WATCHING,
                matchedTransactionId = matchedTransactionId,
                txHash = "0xabc123",
                blockNumber = 21_000_000L,
                chainKey = "EVM_1",
                logIndex = 3,
            )

        assertEquals(EntryStatus.WATCHING, settlement.status)
        assertEquals("EVM_1", settlement.chainKey)
        assertEquals(3, settlement.logIndex)
        assertNull(settlement.confirmedAt)
        assertNull(settlement.confirmationSource)
        assertNull(settlement.reversalTransactionId)
    }

    @Test
    fun `REORGED settlement is additive and preserves original evidence`() {
        val matchedTransactionId = TransactionId.generate()
        val reversalTransactionId = TransactionId.generate()
        val reorgedAt = Instant.now()

        val initiallySettled =
            pendingSettlement().copy(
                status = EntryStatus.SETTLED,
                matchedTransactionId = matchedTransactionId,
                txHash = "0xabc123",
                blockNumber = 21_000_000L,
                confirmedAt = now,
                chainKey = "EVM_1",
                logIndex = 3,
                confirmationSource = "finalized_tag",
            )
        val settlement =
            initiallySettled.copy(
                status = EntryStatus.REORGED,
                reversalTransactionId = reversalTransactionId,
                reorgedAt = reorgedAt,
            )

        assertEquals(EntryStatus.REORGED, settlement.status)
        assertEquals(reversalTransactionId, settlement.reversalTransactionId)
        assertEquals(reorgedAt, settlement.reorgedAt)
        // Original evidence is preserved, not wiped, by the additive marker.
        assertEquals("0xabc123", settlement.txHash)
        assertEquals(21_000_000L, settlement.blockNumber)
        assertEquals("finalized_tag", settlement.confirmationSource)
    }

    @Test
    fun `SETTLED settlement carries on-chain proof fields`() {
        val matchedTransactionId = TransactionId.generate()
        val confirmedAt = Instant.now()

        val settlement =
            pendingSettlement().copy(
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
