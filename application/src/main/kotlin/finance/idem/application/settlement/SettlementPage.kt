package finance.idem.application.settlement

import finance.idem.core.ledger.Settlement

data class SettlementPage(
    val settlements: List<Settlement>,
    val nextCursor: String?,
)
