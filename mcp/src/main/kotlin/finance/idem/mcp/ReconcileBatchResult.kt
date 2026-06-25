package finance.idem.mcp

data class ReconcileBatchResult(
    val matched: Int,
    val unmatched: Int,
    val exceptions: List<String>,
    val settlementIds: List<String>,
)
