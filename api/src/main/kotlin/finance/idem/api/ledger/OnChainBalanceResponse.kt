package finance.idem.api.ledger

import finance.idem.core.StablecoinToken
import finance.idem.core.ledger.OnChainBalance
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

data class OnChainBalanceResponse(
    @Schema(description = "Stablecoin token")
    val token: StablecoinToken,
    @Schema(description = "Net on-chain balance for this token, summed across all chains")
    val amount: BigDecimal,
) {
    companion object {
        fun from(balance: OnChainBalance) =
            OnChainBalanceResponse(
                token = balance.token,
                amount = balance.amount.value,
            )
    }
}
