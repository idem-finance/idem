package finance.idem.application.ledger

import finance.idem.core.AccountId

sealed class PostTransactionError(message: String) : Exception(message) {
    class AccountNotFound(val accountId: AccountId) :
        PostTransactionError("Account not found: ${accountId.value}")

    class IdempotencyConflict(val key: String) :
        PostTransactionError("Idempotency conflict — a request with key '$key' is already in progress")

    class InvariantViolation(val detail: String) :
        PostTransactionError(detail)
}
