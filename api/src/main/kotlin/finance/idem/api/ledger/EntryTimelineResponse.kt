package finance.idem.api.ledger

import finance.idem.application.ledger.EntryPage
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class EntryTimelineResponse(
    @Schema(description = "Account UUID")
    val accountId: UUID,
    @Schema(description = "Journal lines for this page, ordered createdAt DESC, id DESC")
    val entries: List<JournalLineResponse>,
    @Schema(description = "Opaque cursor for the next page, or null if this is the last page")
    val nextCursor: String?,
) {
    companion object {
        fun from(page: EntryPage) =
            EntryTimelineResponse(
                accountId = page.accountId.value,
                entries = page.entries.map { JournalLineResponse.from(it) },
                nextCursor = page.nextCursor,
            )
    }
}
