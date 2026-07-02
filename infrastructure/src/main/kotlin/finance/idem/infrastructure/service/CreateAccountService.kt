package finance.idem.infrastructure.service

import finance.idem.application.ledger.CreateAccountCommand
import finance.idem.application.ledger.CreateAccountUseCase
import finance.idem.core.AccountId
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
@Transactional
class CreateAccountService(
    private val accountRepository: AccountRepository,
) : CreateAccountUseCase {
    override fun execute(cmd: CreateAccountCommand): Result<Account> {
        val account =
            Account.create(
                id = AccountId.generate(),
                tenantId = cmd.tenantId,
                name = cmd.name,
                description = cmd.description,
                currency = cmd.currency,
                type = cmd.type,
                createdAt = Instant.now(),
                createdBy = cmd.createdBy,
            )
        accountRepository.save(account)
        return Result.success(account)
    }
}
