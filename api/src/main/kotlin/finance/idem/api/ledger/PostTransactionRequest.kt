package finance.idem.api.ledger

import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.core.TenantId
import io.swagger.v3.oas.annotations.media.Schema

data class PostTransactionRequest(
    @Schema(description = "Journal lines — must be balanced (debits == credits per currency)", minLength = 2)
    val lines: List<JournalLineRequestDto>,
    @Schema(description = "Arbitrary key-value metadata attached to the transaction")
    val metadata: Map<String, String> = emptyMap(),
) {
    fun toCommand(tenantId: TenantId, idempotencyKey: String): PostTransactionCommand =
        PostTransactionCommand(
            tenantId = tenantId,
            idempotencyKey = idempotencyKey,
            lines = lines.map { it.toDomain() },
            createdBy = "api",
            metadata = metadata,
        )
}
