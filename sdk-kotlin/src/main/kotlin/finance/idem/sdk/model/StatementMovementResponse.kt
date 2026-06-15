package finance.idem.sdk.model

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class StatementMovementResponse(
    val transactionId: UUID,
    val type: EntryType,
    val amount: BigDecimal,
    val description: String?,
    val occurredAt: Instant,
)