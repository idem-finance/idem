package finance.idem.application.ledger

sealed class GetEntriesError(
    message: String,
) : Exception(message)
