package finance.idem.application.ledger

sealed class GetBalanceError(message: String) : Exception(message)
