package finance.idem.application.ledger

import finance.idem.core.AccountId

class DescribeAccountAccountNotFound(
    accountId: AccountId,
) : Exception("Account not found: ${accountId.value}")
