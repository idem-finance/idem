package finance.idem.api.ledger

import finance.idem.application.ledger.Balance
import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.ledger.OnChainBalance
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class BalanceResponseTest {
    @Test
    fun `from produces correct dto including on-chain breakdown`() {
        val accountId = AccountId(UUID.randomUUID())
        val computedAt = Instant.parse("2026-05-28T12:00:00Z")
        val balance =
            Balance(
                accountId = accountId,
                currency = FiatCurrency.BRL,
                amount = MonetaryAmount.of("350.00"),
                normalBalance = EntryType.DEBIT,
                computedAt = computedAt,
                onChainBalances = listOf(OnChainBalance(StablecoinToken.USDC, MonetaryAmount.of("2.50"))),
            )

        val dto = BalanceResponse.from(balance)

        assertEquals(accountId.value, dto.accountId)
        assertEquals(FiatCurrency.BRL, dto.currency)
        assertEquals(BigDecimal("350.00"), dto.amount)
        assertEquals(EntryType.DEBIT, dto.normalBalance)
        assertEquals(computedAt, dto.computedAt)
        assertEquals(listOf(OnChainBalanceResponse(StablecoinToken.USDC, BigDecimal("2.50"))), dto.onChainBalances)

        val fullCopy = dto.copy()
        val partialCopy = dto.copy(amount = BigDecimal("999.00"))
        assertEquals(dto, fullCopy)
        assert(dto != partialCopy)
        assert(dto != null)
        assert(dto.toString().contains("BRL"))
        assertEquals(dto.hashCode(), fullCopy.hashCode())

        val (respAccountId, currency, amount) = dto
        assertEquals(accountId.value, respAccountId)
        assertEquals(FiatCurrency.BRL, currency)
        assertEquals(BigDecimal("350.00"), amount)
    }

    @Test
    fun `from defaults onChainBalances to empty list when balance has none`() {
        val balance =
            Balance(
                accountId = AccountId(UUID.randomUUID()),
                currency = FiatCurrency.USD,
                amount = MonetaryAmount.of("0"),
                normalBalance = EntryType.DEBIT,
                computedAt = Instant.now(),
            )

        assertEquals(emptyList(), BalanceResponse.from(balance).onChainBalances)
    }

    @Test
    fun `onChainBalances defaults to empty list when omitted from the constructor`() {
        val dto = BalanceResponse(UUID.randomUUID(), FiatCurrency.USD, BigDecimal("0"), EntryType.DEBIT, Instant.now())
        assertEquals(emptyList(), dto.onChainBalances)
    }
}
