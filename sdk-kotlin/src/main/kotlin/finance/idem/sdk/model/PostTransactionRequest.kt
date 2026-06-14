package finance.idem.sdk.model

data class PostTransactionRequest(
    val lines: List<JournalLineRequest>,
    val metadata: Map<String, String> = emptyMap(),
)