package finance.idem.core.ledger

import finance.idem.core.AccountId
import finance.idem.core.TenantId

interface AccountRepository {
    fun findById(id: AccountId, tenantId: TenantId): Account?
    fun save(account: Account): Account
    fun findAllByTenantId(tenantId: TenantId): List<Account>
    fun existsById(id: AccountId, tenantId: TenantId): Boolean
    fun findExistingIds(ids: Set<AccountId>, tenantId: TenantId): Set<AccountId>
}
