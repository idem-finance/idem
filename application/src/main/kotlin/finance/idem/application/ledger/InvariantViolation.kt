package finance.idem.application.ledger

class InvariantViolation(
    val detail: String,
) : PostTransactionError(detail)
