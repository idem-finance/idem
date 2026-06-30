package finance.idem.application.settlement

import finance.idem.core.AccountId

class AccountNotFoundForSettlement(val accountId: AccountId) :
    Exception("Account ${accountId.value} not found for this tenant")
