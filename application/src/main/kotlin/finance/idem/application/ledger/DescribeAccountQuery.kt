package finance.idem.application.ledger

import finance.idem.core.AccountId
import finance.idem.core.TenantId

data class DescribeAccountQuery(
    val accountId: AccountId,
    val tenantId: TenantId,
)
