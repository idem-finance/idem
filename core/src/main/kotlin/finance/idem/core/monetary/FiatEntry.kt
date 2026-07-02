package finance.idem.core.monetary

import finance.idem.core.FiatCurrency
import finance.idem.core.LedgerInvariantViolation
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail

data class FiatEntry(
    override val amount: MonetaryAmount,
    val currency: FiatCurrency,
    val rail: PaymentRail,
    val bankReference: String? = null,
) : MonetaryEntry() {
    init {
        if (!amount.isPositive()) {
            throw LedgerInvariantViolation(
                "FiatEntry amount must be positive, got ${amount.value}",
            )
        }
    }
}
