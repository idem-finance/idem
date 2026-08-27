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
    // Portion of `amount` still WATCHING an unconfirmed on-chain credit — not yet past its
    // chain's finality bound, so it could still be reversed by a reorg. Treat
    // amount - pendingFinalityAmount as the reorg-safe figure before acting on these funds.
    val pendingFinalityAmount: String,
)
