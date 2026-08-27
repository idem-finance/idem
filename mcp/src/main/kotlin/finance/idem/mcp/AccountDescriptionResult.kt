package finance.idem.mcp

data class AccountDescriptionResult(
    val accountId: String,
    val name: String,
    val description: String?,
    val currency: String,
    val entryCount: Long,
    val lastActivityAt: String?,
    val balanceCurrency: String,
    val balanceAmount: String,
    val onChainBalances: List<OnChainTokenBalance> = emptyList(),
)
