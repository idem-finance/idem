package finance.idem.mcp

data class BalanceResult(
    val accountId: String,
    val currency: String,
    val amount: String,
    val computedAt: String,
)
