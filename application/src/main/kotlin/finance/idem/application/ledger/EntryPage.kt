package finance.idem.application.ledger

import finance.idem.core.AccountId
import finance.idem.core.ledger.JournalLine

data class EntryPage(
    val accountId: AccountId,
    val entries: List<JournalLine>,
    val nextCursor: String?,
)
