package finance.idem.api.ledger

import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.monetary.FiatEntry
import java.math.BigDecimal

data class FiatEntryDto(
    val amount: BigDecimal,
    val currency: FiatCurrency,
    val rail: PaymentRail,
    val bankReference: String? = null,
) : MonetaryEntryRequestDto() {
    override fun toDomain() =
        FiatEntry(
            amount = MonetaryAmount.of(amount),
            currency = currency,
            rail = rail,
            bankReference = bankReference,
        )
}
