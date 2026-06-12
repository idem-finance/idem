package finance.idem.api.ledger

import finance.idem.application.ledger.StatementMovement
import finance.idem.core.EntryType
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class StatementMovementResponse(
    @Schema(description = "Transaction UUID this movement belongs to")
    val transactionId: UUID,
    @Schema(description = "DEBIT or CREDIT")
    val type: EntryType,
    @Schema(description = "Movement amount")
    val amount: BigDecimal,
    @Schema(description = "Optional human-readable description of this movement")
    val description: String?,
    @Schema(description = "Timestamp when the underlying transaction occurred")
    val createdAt: Instant,
) {
    companion object {
        fun from(movement: StatementMovement) = StatementMovementResponse(
            transactionId = movement.transactionId.value,
            type = movement.type,
            amount = movement.amount.value,
            description = movement.description,
            createdAt = movement.occurredAt,
        )
    }
}
