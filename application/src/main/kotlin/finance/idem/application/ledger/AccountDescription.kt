package finance.idem.application.ledger

import finance.idem.core.AccountId
import finance.idem.core.FiatCurrency
import java.time.Instant

data class AccountDescription(
    val accountId: AccountId,
    val name: String,
    val description: String?,
    val currency: FiatCurrency,
    val entryCount: Long,
    val lastActivityAt: Instant?,
    val balance: Balance,
)
