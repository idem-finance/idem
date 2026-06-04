package finance.idem.application.ledger

import finance.idem.core.AccountId

class BalanceAccountNotFound(val accountId: AccountId) :
    QueryBalanceError("Account not found: ${accountId.value}")
