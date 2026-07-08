package finance.idem.sdk.model

import java.util.UUID

/**
 * [outcome] is kept as the server's raw string (e.g. "SETTLED", "UNMATCHED", "NOT_APPLICABLE",
 * "NOT_FOUND") rather than a client-side enum, since the set of outcomes is server-owned and
 * open to future additions.
 */
data class ReconcileBatchItemResponse(
    val transactionId: UUID,
    val outcome: String,
)
