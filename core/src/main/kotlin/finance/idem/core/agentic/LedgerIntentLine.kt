package finance.idem.core.agentic

import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.monetary.MonetaryEntry

/**
 * A pre-flight view of a single ledger movement proposed by an agent.
 *
 * Unlike [finance.idem.core.ledger.JournalLine], this type carries no persistence
 * identity — no UUID, no transactionId, no timestamps. It exists solely to describe
 * the intended effect of a transaction before it is committed, so [PolicyGuard] can
 * evaluate it against a set of [PolicyRule]s.
 */
data class LedgerIntentLine(
    val accountId: AccountId,
    val entryType: EntryType,
    val monetaryEntry: MonetaryEntry,
)
