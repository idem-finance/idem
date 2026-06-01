package finance.idem.infrastructure.persistence

import finance.idem.core.AccountId
import finance.idem.core.FiatCurrency
import finance.idem.core.TenantId
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountRepository
import finance.idem.core.ledger.AccountType
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class AccountRepositoryAdapter(
    private val jpaRepository: AccountJpaRepository,
    private val entityManager: EntityManager,
) : AccountRepository {

    private fun setTenantId(tenantId: TenantId) {
        entityManager.createNativeQuery("SELECT set_config('app.tenant_id', :tid, true)")
            .setParameter("tid", tenantId.value.toString())
            .singleResult
    }

    @Transactional(readOnly = true)
    override fun findById(id: AccountId, tenantId: TenantId): Account? {
        setTenantId(tenantId)
        return jpaRepository.findById(id.value).orElse(null)
            ?.takeIf { it.tenantId == tenantId.value }
            ?.toDomain()
    }

    @Transactional
    override fun save(account: Account): Account {
        setTenantId(account.tenantId)
        jpaRepository.save(account.toEntity())
        return account
    }

    @Transactional(readOnly = true)
    override fun findAllByTenantId(tenantId: TenantId): List<Account> {
        setTenantId(tenantId)
        return jpaRepository.findAllByTenantId(tenantId.value).map { it.toDomain() }
    }

    @Transactional(readOnly = true)
    override fun existsById(id: AccountId, tenantId: TenantId): Boolean {
        setTenantId(tenantId)
        return jpaRepository.findById(id.value).map { it.tenantId == tenantId.value }.orElse(false)
    }

    @Transactional(readOnly = true)
    override fun findExistingIds(ids: Set<AccountId>, tenantId: TenantId): Set<AccountId> {
        if (ids.isEmpty()) return emptySet()
        setTenantId(tenantId)
        return jpaRepository.findExistingIds(ids.map { it.value }, tenantId.value)
            .map { AccountId(it) }
            .toSet()
    }
}

private fun AccountDataModel.toDomain(): Account = Account.reconstitute(
    id = AccountId(id),
    tenantId = TenantId(tenantId),
    name = name,
    description = description,
    currency = FiatCurrency.valueOf(currency),
    type = AccountType.valueOf(type),
    createdAt = createdAt,
    createdBy = createdBy,
    updatedAt = updatedAt,
    updatedBy = updatedBy,
)

private fun Account.toEntity(): AccountDataModel = AccountDataModel(
    id = id.value,
    tenantId = tenantId.value,
    name = name,
    description = description,
    currency = currency.name,
    type = type.name,
    createdAt = createdAt,
    createdBy = createdBy,
    updatedAt = updatedAt,
    updatedBy = updatedBy,
)
