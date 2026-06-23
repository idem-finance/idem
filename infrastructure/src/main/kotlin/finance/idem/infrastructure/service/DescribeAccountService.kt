package finance.idem.infrastructure.service

import finance.idem.application.ledger.AccountDescription
import finance.idem.application.ledger.DescribeAccountAccountNotFound
import finance.idem.application.ledger.DescribeAccountQuery
import finance.idem.application.ledger.DescribeAccountUseCase
import finance.idem.application.ledger.GetBalanceQuery
import finance.idem.application.ledger.GetBalanceUseCase
import finance.idem.core.ledger.AccountRepository
import finance.idem.core.ledger.JournalLineRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class DescribeAccountService(
    private val accountRepository: AccountRepository,
    private val journalLineRepository: JournalLineRepository,
    private val getBalanceUseCase: GetBalanceUseCase,
) : DescribeAccountUseCase {

    override fun execute(query: DescribeAccountQuery): Result<AccountDescription> {
        val account = accountRepository.findById(query.accountId, query.tenantId)
            ?: return Result.failure(DescribeAccountAccountNotFound(query.accountId))

        val entryCount = journalLineRepository.countByAccountId(query.accountId, query.tenantId)

        val lastActivityAt = journalLineRepository.findMostRecentEntry(query.accountId, query.tenantId)?.createdAt

        val balance = getBalanceUseCase.execute(
            GetBalanceQuery(accountId = query.accountId, tenantId = query.tenantId, asOf = null),
        ).getOrElse { return Result.failure(it) }

        return Result.success(
            AccountDescription(
                accountId = account.id,
                name = account.name,
                description = account.description,
                currency = account.currency,
                entryCount = entryCount,
                lastActivityAt = lastActivityAt,
                balance = balance,
            ),
        )
    }
}
