package finance.idem.sdk.model

import java.util.UUID

data class ReconcileBatchRequest(
    val transactionIds: List<UUID>,
)
