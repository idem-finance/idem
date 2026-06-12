package finance.idem.api.ledger

import finance.idem.core.EntryType
import finance.idem.core.ledger.JournalLine
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

data class JournalLineResponseDto(
    @Schema(description = "Journal line UUID")
    val entryId: UUID,
    @Schema(description = "Transaction UUID this entry belongs to")
    val transactionId: UUID,
    @Schema(description = "DEBIT or CREDIT")
    val type: EntryType,
    @Schema(description = "The monetary entry posted (fiat or on-chain)")
    val monetary: MonetaryEntryResponseDto,
    @Schema(description = "Timestamp when the entry was created")
    val createdAt: Instant,
) {
    companion object {
        fun from(line: JournalLine) = JournalLineResponseDto(
            entryId = line.id,
            transactionId = line.transactionId.value,
            type = line.entryType,
            monetary = MonetaryEntryResponseDto.from(line.monetaryEntry),
            createdAt = line.createdAt,
        )
    }
}
