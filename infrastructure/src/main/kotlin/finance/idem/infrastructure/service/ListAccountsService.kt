package finance.idem.infrastructure.service

import finance.idem.application.ledger.ListAccountsQuery
import finance.idem.application.ledger.ListAccountsUseCase
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountRepository
import org.springframework.stereotype.Service

@Service
class ListAccountsService(
    private val accountRepository: AccountRepository,
) : ListAccountsUseCase {
    override fun execute(query: ListAccountsQuery): Result<List<Account>> =
        Result.success(accountRepository.findAllByTenantId(query.tenantId))
}
