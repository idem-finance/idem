package finance.idem.application.ledger

import finance.idem.core.ledger.Account

interface CreateAccountUseCase {
    fun execute(cmd: CreateAccountCommand): Result<Account>
}
