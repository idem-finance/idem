package finance.idem.api.ledger

import finance.idem.core.ChainId
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.StablecoinToken
import finance.idem.core.monetary.FiatEntry
import finance.idem.core.monetary.OnChainEntry
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MonetaryEntryResponseTest {

    @Test
    fun `FiatEntryResponse from produces correct dto`() {
        val entry = FiatEntry(
            amount = MonetaryAmount.of("100.00"),
            currency = FiatCurrency.BRL,
            rail = PaymentRail.PIX,
            bankReference = "ref-001",
        )

        val dto = assertIs<FiatEntryResponse>(MonetaryEntryResponse.from(entry))
        assertEquals(BigDecimal("100.00"), dto.amount)
        assertEquals(FiatCurrency.BRL, dto.currency)
        assertEquals(PaymentRail.PIX, dto.rail)
        assertEquals("ref-001", dto.bankReference)

        val fullCopy = dto.copy()
        val partialCopy = dto.copy(bankReference = "ref-002")
        assertEquals(dto, fullCopy)
        assert(dto != partialCopy)
        assertEquals("ref-002", partialCopy.bankReference)
        assert(dto != null)
        assert(dto.toString().contains("BRL"))
        assertEquals(dto.hashCode(), fullCopy.hashCode())

        val (amount, currency, rail, bankReference) = dto
        assertEquals(BigDecimal("100.00"), amount)
        assertEquals(FiatCurrency.BRL, currency)
        assertEquals(PaymentRail.PIX, rail)
        assertEquals("ref-001", bankReference)
    }

    @Test
    fun `FiatEntryResponse defaults bankReference to null when omitted`() {
        val dto = FiatEntryResponse(BigDecimal("50.00"), FiatCurrency.USD, PaymentRail.ACH)
        assertEquals(null, dto.bankReference)
    }

    @Test
    fun `OnChainEntryResponse from produces correct dto`() {
        val entry = OnChainEntry(
            amount = MonetaryAmount.of("1.000000"),
            token = StablecoinToken.USDC,
            chainId = ChainId.EVM,
            txHash = "0xabc",
            blockNumber = 19_000_000L,
            walletAddress = "0xWallet",
            tokenContract = "0xContract",
            fromAddress = "0xSender",
        )

        val dto = assertIs<OnChainEntryResponse>(MonetaryEntryResponse.from(entry))
        assertEquals(BigDecimal("1.000000"), dto.amount)
        assertEquals(StablecoinToken.USDC, dto.token)
        assertEquals(ChainId.EVM, dto.chainId)
        assertEquals("0xabc", dto.txHash)
        assertEquals(19_000_000L, dto.blockNumber)
        assertEquals("0xWallet", dto.walletAddress)
        assertEquals("0xContract", dto.tokenContract)
        assertEquals("0xSender", dto.fromAddress)

        val fullCopy = dto.copy()
        val partialCopy = dto.copy(fromAddress = "0xOtherSender")
        assertEquals(dto, fullCopy)
        assert(dto != partialCopy)
        assertEquals("0xOtherSender", partialCopy.fromAddress)
        assert(dto != null)
        assert(dto.toString().contains("USDC"))
        assertEquals(dto.hashCode(), fullCopy.hashCode())

        val (amount, token, chainId, txHash, blockNumber, walletAddress, tokenContract, fromAddress) = dto
        assertEquals(BigDecimal("1.000000"), amount)
        assertEquals(StablecoinToken.USDC, token)
        assertEquals(ChainId.EVM, chainId)
        assertEquals("0xabc", txHash)
        assertEquals(19_000_000L, blockNumber)
        assertEquals("0xWallet", walletAddress)
        assertEquals("0xContract", tokenContract)
        assertEquals("0xSender", fromAddress)
    }

    @Test
    fun `OnChainEntryResponse defaults fromAddress to null when omitted`() {
        val dto = OnChainEntryResponse(
            amount = BigDecimal("1.000000"),
            token = StablecoinToken.USDC,
            chainId = ChainId.EVM,
            txHash = "0xabc",
            blockNumber = 19_000_000L,
            walletAddress = "0xWallet",
            tokenContract = "0xContract",
        )
        assertEquals(null, dto.fromAddress)
    }
}
