package finance.idem.mcp

data class BalanceResult(
    val accountId: String,
    val currency: String,
    val amount: String,
    val computedAt: String,
    val onChainBalances: List<OnChainTokenBalance> = emptyList(),
)

data class OnChainTokenBalance(
    val token: String,
    val amount: String,
)
