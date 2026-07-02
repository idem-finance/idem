package finance.idem.api.ledger

import finance.idem.application.ledger.JournalLineRequest
import finance.idem.core.AccountId
import finance.idem.core.EntryType
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class JournalLineRequestDto(
    @Schema(description = "Account UUID", required = true)
    val accountId: UUID,
    @Schema(description = "DEBIT or CREDIT", required = true)
    val entryType: EntryType,
    @Schema(description = "Monetary entry — FIAT or ONCHAIN", required = true)
    val monetaryEntry: MonetaryEntryRequestDto,
    val description: String? = null,
) {
    fun toDomain() =
        JournalLineRequest(
            accountId = AccountId(accountId),
            entryType = entryType,
            monetaryEntry = monetaryEntry.toDomain(),
            description = description,
        )
}
