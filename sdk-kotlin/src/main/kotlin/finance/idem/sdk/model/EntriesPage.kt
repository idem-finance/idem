package finance.idem.sdk.model

import java.util.UUID

data class EntriesPage(
    val accountId: UUID,
    val entries: List<JournalLineResponse>,
    val nextCursor: String?,
)
