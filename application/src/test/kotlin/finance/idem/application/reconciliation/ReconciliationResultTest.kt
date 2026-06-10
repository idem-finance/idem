package finance.idem.application.reconciliation

import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.Settlement
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ReconciliationResultTest {

    private val watchedWallet = "5FHwkrdxkTEBqVTBmRjfBknDiCMWB6cYPQCGt1tnk9HS"

    private fun pendingSettlement() = Settlement(
        id = UUID.randomUUID(),
        tenantId = TenantId.generate(),
        accountId = AccountId.generate(),
        amount = MonetaryAmount.of("100.000000"),
        token = StablecoinToken.USDC,
        chainId = ChainId.SOLANA,
        walletAddress = watchedWallet,
        status = EntryStatus.PENDING,
        createdAt = Instant.now(),
        createdBy = "api-user",
    )

    @Test
    fun `NotApplicable is a singleton value`() {
        assertEquals(ReconciliationResult.NotApplicable, ReconciliationResult.NotApplicable)
    }

    @Test
    fun `Settled carries the matched settlement`() {
        val settlement = pendingSettlement().copy(status = EntryStatus.SETTLED)

        val result = ReconciliationResult.Settled(settlement)

        assertEquals(settlement, result.settlement)
        assertEquals(ReconciliationResult.Settled(settlement), result)
    }

    @Test
    fun `Unmatched carries the orphan settlement`() {
        val settlement = pendingSettlement().copy(status = EntryStatus.UNMATCHED)

        val result = ReconciliationResult.Unmatched(settlement)

        assertEquals(settlement, result.settlement)
        assertEquals(ReconciliationResult.Unmatched(settlement), result)
    }

    @Test
    fun `Settled and Unmatched wrapping the same settlement are not equal`() {
        val settlement = pendingSettlement()

        assertNotEquals<ReconciliationResult>(
            ReconciliationResult.Settled(settlement),
            ReconciliationResult.Unmatched(settlement),
        )
    }
}
