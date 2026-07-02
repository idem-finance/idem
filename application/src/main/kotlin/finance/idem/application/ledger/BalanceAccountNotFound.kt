package finance.idem.application.ledger

import finance.idem.core.AccountId

class BalanceAccountNotFound(
    val accountId: AccountId,
) : GetBalanceError("Account not found: ${accountId.value}")
