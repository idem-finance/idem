package finance.idem.api.ledger

import finance.idem.application.ledger.Balance
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class BalanceResponse(
    @Schema(description = "Account UUID")
    val accountId: UUID,
    @Schema(description = "ISO 4217 currency code")
    val currency: FiatCurrency,
    @Schema(description = "Net balance amount")
    val amount: BigDecimal,
    @Schema(description = "DEBIT for asset/expense accounts, CREDIT for liability/equity/revenue")
    val normalBalance: EntryType,
    @Schema(description = "Timestamp when the balance was computed")
    val computedAt: Instant,
    @Schema(description = "Net on-chain balance per stablecoin token, summed across all chains — never combined with the fiat amount above")
    val onChainBalances: List<OnChainBalanceResponse> = emptyList(),
) {
    companion object {
        fun from(balance: Balance) =
            BalanceResponse(
                accountId = balance.accountId.value,
                currency = balance.currency,
                amount = balance.amount.value,
                normalBalance = balance.normalBalance,
                computedAt = balance.computedAt,
                onChainBalances = balance.onChainBalances.map(OnChainBalanceResponse::from),
            )
    }
}
