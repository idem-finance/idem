package finance.idem.application.ledger

import finance.idem.core.TenantId
import finance.idem.core.ledger.Account

data class ListAccountsQuery(
    val tenantId: TenantId,
)

interface ListAccountsUseCase {
    fun execute(query: ListAccountsQuery): Result<List<Account>>
}
