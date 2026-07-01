package finance.idem.application.ledger

sealed class PostTransactionError(
    message: String,
) : Exception(message)
