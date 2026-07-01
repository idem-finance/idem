package finance.idem.sdk.model

import java.time.Instant
import java.util.UUID

data class JournalLineResponse(
    val entryId: UUID,
    val transactionId: UUID,
    val type: EntryType,
    val monetary: MonetaryEntryResponse,
    val description: String? = null,
    val createdAt: Instant,
)
