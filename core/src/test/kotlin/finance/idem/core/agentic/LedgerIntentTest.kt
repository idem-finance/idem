package finance.idem.core.agentic

import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.monetary.FiatEntry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LedgerIntentTest {
    @Test
    fun `prior totals default to ZERO when omitted`() {
        val intent = LedgerIntent(lines = emptyList())
        assertEquals(MonetaryAmount.ZERO, intent.priorSessionDebitTotal)
        assertEquals(MonetaryAmount.ZERO, intent.priorHourlyDebitTotal)
    }

    @Test
    fun `constructs with explicit prior totals`() {
        val sessionTotal = MonetaryAmount.of("500.00")
        val hourlyTotal = MonetaryAmount.of("200.00")
        val intent =
            LedgerIntent(
                lines = emptyList(),
                priorSessionDebitTotal = sessionTotal,
                priorHourlyDebitTotal = hourlyTotal,
            )
        assertEquals(sessionTotal, intent.priorSessionDebitTotal)
        assertEquals(hourlyTotal, intent.priorHourlyDebitTotal)
    }

    @Test
    fun `empty lines list is valid`() {
        val intent = LedgerIntent(lines = emptyList())
        assertTrue(intent.lines.isEmpty())
    }

    @Test
    fun `constructs with lines`() {
        val line =
            LedgerIntentLine(
                accountId = AccountId.generate(),
                entryType = EntryType.DEBIT,
                monetaryEntry =
                    FiatEntry(
                        amount = MonetaryAmount.of("100.00"),
                        currency = FiatCurrency.USD,
                        rail = PaymentRail.ACH,
                    ),
            )
        val intent = LedgerIntent(lines = listOf(line))
        assertEquals(1, intent.lines.size)
        assertEquals(line, intent.lines.first())
    }
}
