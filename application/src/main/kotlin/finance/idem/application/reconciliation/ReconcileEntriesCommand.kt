package finance.idem.application.reconciliation

import finance.idem.core.AccountId
import finance.idem.core.TenantId
import java.time.Instant

data class ReconcileEntriesCommand(
    val tenantId: TenantId,
    val accountId: AccountId? = null,
    val from: Instant,
    val to: Instant,
)
