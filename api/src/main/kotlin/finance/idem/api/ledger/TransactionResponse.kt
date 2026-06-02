package finance.idem.api.ledger

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class PostTransactionResponse(
    @Schema(description = "The committed transaction ID")
    val transactionId: UUID,
)

data class ErrorResponse(
    @Schema(description = "Machine-readable error code")
    val code: String,
    @Schema(description = "Human-readable error message")
    val message: String,
)
