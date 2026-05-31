package finance.idem.core.ledger

import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.TransactionId
import finance.idem.core.monetary.MonetaryEntry
import java.time.Instant
import java.util.UUID

data class JournalLine(
    val id: UUID,
    val transactionId: TransactionId,
    val accountId: AccountId,
    val entryType: EntryType,
    val monetaryEntry: MonetaryEntry,
    val description: String? = null,
    val createdAt: Instant,
    val createdBy: String,
)
