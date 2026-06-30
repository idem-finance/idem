package finance.idem.api.settlement

data class SettlementListResponse(
    val settlements: List<SettlementResponse>,
    val nextCursor: String?,
)
