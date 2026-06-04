package finance.idem.application.ledger

import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.monetary.MonetaryEntry

data class JournalLineRequest(
    val accountId: AccountId,
    val entryType: EntryType,
    val monetaryEntry: MonetaryEntry,
    val description: String? = null,
)
