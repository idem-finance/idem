package finance.idem.mcp

data class EntryListResult(
    val accountId: String,
    val entries: List<EntryItem>,
    val nextCursor: String?,
)
