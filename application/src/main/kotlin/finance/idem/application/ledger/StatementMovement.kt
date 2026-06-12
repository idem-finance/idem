package finance.idem.application.ledger

import finance.idem.core.EntryType
import finance.idem.core.MonetaryAmount
import finance.idem.core.TransactionId
import java.time.Instant

data class StatementMovement(
    val transactionId: TransactionId,
    val type: EntryType,
    val amount: MonetaryAmount,
    val description: String?,
    val occurredAt: Instant,
)
