package finance.idem.application.ledger

import finance.idem.core.AccountId
import finance.idem.core.TenantId
import java.time.Instant

data class GetEntriesQuery(
    val accountId: AccountId,
    val tenantId: TenantId,
    val from: Instant? = null,
    val to: Instant? = null,
    val limit: Int = 50,
    val cursor: String? = null,
)
