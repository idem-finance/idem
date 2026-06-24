package finance.idem.mcp

data class EntryItem(
    val id: String,
    val transactionId: String,
    val entryType: String,
    val amount: String,
    val currency: String,
    val description: String?,
    val createdAt: String,
)
