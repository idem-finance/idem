package finance.idem.application.ledger

import finance.idem.core.AccountId

class StatementAccountNotFound(
    val accountId: AccountId,
) : GenerateStatementError("Account not found: ${accountId.value}")
