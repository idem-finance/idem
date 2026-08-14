package finance.idem.sdk.model

import java.math.BigDecimal

data class OnChainBalanceResponse(
    val token: StablecoinToken,
    val amount: BigDecimal,
)
