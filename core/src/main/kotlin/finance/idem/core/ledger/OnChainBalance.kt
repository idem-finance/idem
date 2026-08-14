package finance.idem.core.ledger

import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken

data class OnChainBalance(
    val token: StablecoinToken,
    val amount: MonetaryAmount,
)
