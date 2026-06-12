package finance.idem.api.ledger

import finance.idem.core.FiatCurrency
import finance.idem.core.PaymentRail
import finance.idem.core.monetary.FiatEntry
import java.math.BigDecimal

data class FiatEntryResponseDto(
    val amount: BigDecimal,
    val currency: FiatCurrency,
    val rail: PaymentRail,
    val bankReference: String? = null,
) : MonetaryEntryResponseDto() {
    companion object {
        fun from(entry: FiatEntry) = FiatEntryResponseDto(
            amount = entry.amount.value,
            currency = entry.currency,
            rail = entry.rail,
            bankReference = entry.bankReference,
        )
    }
}
