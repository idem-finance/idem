package finance.idem.api.ledger

import finance.idem.core.ChainId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.PaymentRail
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.monetary.FiatEntry
import finance.idem.core.monetary.OnChainEntry
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TransactionRequestTest {

    @Test
    fun `FiatEntryDto toDomain produces correct FiatEntry`() {
        val dto = FiatEntryDto(
            amount = BigDecimal("100.00"),
            currency = FiatCurrency.BRL,
            rail = PaymentRail.PIX,
            bankReference = "ref-001",
        )
        val entry = assertIs<FiatEntry>(dto.toDomain())
        assertEquals(FiatCurrency.BRL, entry.currency)
        assertEquals(PaymentRail.PIX, entry.rail)
        assertEquals("ref-001", entry.bankReference)
    }

    @Test
    fun `OnChainEntryDto toDomain produces correct OnChainEntry`() {
        val dto = OnChainEntryDto(
            amount = BigDecimal("180.00"),
            token = StablecoinToken.USDC,
            chainId = ChainId.EVM,
            txHash = "0xabc",
            blockNumber = 19_000_000L,
            walletAddress = "0xWallet",
            tokenContract = "0xContract",
        )
        val entry = assertIs<OnChainEntry>(dto.toDomain())
        assertEquals(StablecoinToken.USDC, entry.token)
        assertEquals(ChainId.EVM, entry.chainId)
        assertEquals("0xabc", entry.txHash)

        val fullCopy = dto.copy()
        val partialCopy = dto.copy(txHash = "0xdef", blockNumber = 20_000_000L)
        assertEquals(dto, fullCopy)
        assert(dto != partialCopy)
        assertEquals("0xdef", partialCopy.txHash)
        assert(dto != null)
        assert(dto.toString().contains("USDC"))
        assertEquals(dto.hashCode(), fullCopy.hashCode())

        val (amount, token, chainId, txHash, blockNumber, walletAddress, tokenContract) = dto
        assertEquals(BigDecimal("180.00"), amount)
        assertEquals(StablecoinToken.USDC, token)
        assertEquals(ChainId.EVM, chainId)
        assertEquals("0xabc", txHash)
        assertEquals(19_000_000L, blockNumber)
        assertEquals("0xWallet", walletAddress)
        assertEquals("0xContract", tokenContract)
    }

    @Test
    fun `toCommand maps all lines to domain`() {
        val tenantId = TenantId.generate()
        val accountId = UUID.randomUUID()
        val request = PostTransactionRequest(
            lines = listOf(
                JournalLineRequestDto(
                    accountId = accountId,
                    entryType = EntryType.DEBIT,
                    monetaryEntry = FiatEntryDto(
                        BigDecimal("50"), FiatCurrency.USD, PaymentRail.ACH,
                    ),
                ),
            ),
        )
        val cmd = request.toCommand(tenantId, "idem-1")
        assertEquals(tenantId, cmd.tenantId)
        assertEquals("idem-1", cmd.idempotencyKey)
        assertEquals(1, cmd.lines.size)
    }
}
