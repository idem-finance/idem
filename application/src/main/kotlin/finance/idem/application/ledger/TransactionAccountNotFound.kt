package finance.idem.application.ledger

import finance.idem.core.AccountId

class TransactionAccountNotFound(
    val accountId: AccountId,
) : PostTransactionError("Account not found: ${accountId.value}")
