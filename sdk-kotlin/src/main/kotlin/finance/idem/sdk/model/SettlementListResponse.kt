package finance.idem.sdk.model

data class SettlementListResponse(
    val settlements: List<SettlementResponse>,
    val nextCursor: String?,
)
