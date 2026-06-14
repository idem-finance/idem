package finance.idem.sdk.model

import java.util.UUID

data class JournalLineRequest(
    val accountId: UUID,
    val entryType: EntryType,
    val monetaryEntry: MonetaryEntryRequest,
    val description: String? = null,
)