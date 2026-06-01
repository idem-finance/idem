package finance.idem.application.ledger

import finance.idem.core.AccountId

sealed class QueryBalanceError(message: String) : Exception(message) {
    class AccountNotFound(val accountId: AccountId) :
        QueryBalanceError("Account not found: ${accountId.value}")
}
