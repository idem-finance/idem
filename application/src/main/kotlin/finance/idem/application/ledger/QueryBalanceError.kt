package finance.idem.application.ledger

sealed class QueryBalanceError(message: String) : Exception(message)
