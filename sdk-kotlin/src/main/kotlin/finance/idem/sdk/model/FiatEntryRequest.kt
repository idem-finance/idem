package finance.idem.sdk.model

import java.math.BigDecimal

data class FiatEntryRequest(
    val amount: BigDecimal,
    val currency: FiatCurrency,
    val rail: PaymentRail,
    val bankReference: String? = null,
) : MonetaryEntryRequest()
