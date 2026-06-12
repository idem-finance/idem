package finance.idem.application.ledger

import finance.idem.core.AccountId
import finance.idem.core.TenantId
import java.time.Instant

data class GetBalanceQuery(
    val accountId: AccountId,
    val tenantId: TenantId,
    val asOf: Instant? = null,
)
