package finance.idem.application.ledger

sealed class GenerateStatementError(
    message: String,
) : Exception(message)
