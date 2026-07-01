package finance.idem.core.monetary

import finance.idem.core.ChainId
import finance.idem.core.FiatCurrency
import finance.idem.core.LedgerInvariantViolation
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.StablecoinToken
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MonetaryEntryTest {
    // ── FiatEntry ────────────────────────────────────────────────────────────

    @Test
    fun `FiatEntry valid with all fields`() {
        val entry =
            FiatEntry(
                amount = MonetaryAmount.of("1000.00"),
                currency = FiatCurrency.BRL,
                rail = PaymentRail.PIX,
                bankReference = "REF-001",
            )
        assertEquals(MonetaryAmount.of("1000.00"), entry.amount)
        assertEquals(FiatCurrency.BRL, entry.currency)
        assertEquals(PaymentRail.PIX, entry.rail)
        assertEquals("REF-001", entry.bankReference)
    }

    @Test
    fun `FiatEntry valid without optional bankReference`() {
        val entry =
            FiatEntry(
                amount = MonetaryAmount.of("500"),
                currency = FiatCurrency.USD,
                rail = PaymentRail.WIRE,
            )
        assertNull(entry.bankReference)
    }

    @Test
    fun `FiatEntry rejects zero amount`() {
        assertThrows<LedgerInvariantViolation> {
            FiatEntry(
                amount = MonetaryAmount.ZERO,
                currency = FiatCurrency.BRL,
                rail = PaymentRail.PIX,
            )
        }
    }

    @Test
    fun `FiatEntry rejects negative amount`() {
        assertThrows<LedgerInvariantViolation> {
            FiatEntry(
                amount = MonetaryAmount.of("-1.00"),
                currency = FiatCurrency.USD,
                rail = PaymentRail.ACH,
            )
        }
    }

    // ── OnChainEntry ─────────────────────────────────────────────────────────

    @Test
    fun `OnChainEntry valid`() {
        val entry = validOnChainEntry()
        assertEquals(MonetaryAmount.of("180.00"), entry.amount)
        assertEquals(StablecoinToken.USDC, entry.token)
        assertEquals(ChainId.EVM, entry.chainId)
        assertEquals("0xabc123", entry.txHash)
        assertEquals(19_000_000L, entry.blockNumber)
        assertEquals("0xWallet", entry.walletAddress)
        assertEquals("0xContract", entry.tokenContract)
    }

    @Test
    fun `OnChainEntry rejects zero amount`() {
        assertThrows<LedgerInvariantViolation> {
            validOnChainEntry(amount = MonetaryAmount.ZERO)
        }
    }

    @Test
    fun `OnChainEntry rejects negative amount`() {
        assertThrows<LedgerInvariantViolation> {
            validOnChainEntry(amount = MonetaryAmount.of("-0.01"))
        }
    }

    @Test
    fun `OnChainEntry rejects blank txHash`() {
        assertThrows<LedgerInvariantViolation> {
            validOnChainEntry(txHash = "   ")
        }
    }

    @Test
    fun `OnChainEntry rejects blank walletAddress`() {
        assertThrows<LedgerInvariantViolation> {
            validOnChainEntry(walletAddress = "")
        }
    }

    @Test
    fun `OnChainEntry rejects blank tokenContract`() {
        assertThrows<LedgerInvariantViolation> {
            validOnChainEntry(tokenContract = "")
        }
    }

    // ── Sealed class exhaustiveness ───────────────────────────────────────────

    @Test
    fun `when expression on MonetaryEntry is exhaustive without else`() {
        val fiat: MonetaryEntry =
            FiatEntry(
                amount = MonetaryAmount.of("100"),
                currency = FiatCurrency.BRL,
                rail = PaymentRail.PIX,
            )
        val onChain: MonetaryEntry = validOnChainEntry()

        // If a new subtype were added and this when had no else, it would fail to compile.
        // This test documents and exercises both branches.
        fun label(entry: MonetaryEntry): String =
            when (entry) {
                is FiatEntry -> "fiat"
                is OnChainEntry -> "on-chain"
            }

        assertEquals("fiat", label(fiat))
        assertEquals("on-chain", label(onChain))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun validOnChainEntry(
        amount: MonetaryAmount = MonetaryAmount.of("180.00"),
        txHash: String = "0xabc123",
        walletAddress: String = "0xWallet",
        tokenContract: String = "0xContract",
    ) = OnChainEntry(
        amount = amount,
        token = StablecoinToken.USDC,
        chainId = ChainId.EVM,
        txHash = txHash,
        blockNumber = 19_000_000L,
        walletAddress = walletAddress,
        tokenContract = tokenContract,
    )
}
