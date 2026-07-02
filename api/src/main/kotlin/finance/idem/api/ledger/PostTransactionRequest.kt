package finance.idem.api.ledger

import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.core.TenantId
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

data class PostTransactionRequest(
    @field:Size(min = 2, max = 1000, message = "lines must contain between 2 and 1000 entries")
    @Schema(description = "Journal lines — must be balanced (debits == credits per currency)", minLength = 2)
    val lines: List<JournalLineRequestDto>,
    @field:Size(max = 50, message = "metadata must not exceed 50 entries")
    @Schema(description = "Arbitrary key-value metadata attached to the transaction")
    val metadata: Map<String, String> = emptyMap(),
) {
    fun toCommand(
        tenantId: TenantId,
        idempotencyKey: String,
    ): PostTransactionCommand =
        PostTransactionCommand(
            tenantId = tenantId,
            idempotencyKey = idempotencyKey,
            lines = lines.map { it.toDomain() },
            createdBy = "api",
            metadata = metadata,
        )
}
