package finance.idem.application.ledger

import finance.idem.core.AccountId

class EntriesAccountNotFound(val accountId: AccountId) :
    QueryEntriesError("Account not found: ${accountId.value}")
