package finance.idem.application.ledger

sealed class QueryEntriesError(message: String) : Exception(message)
