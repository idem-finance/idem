package finance.idem.application.ledger

import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.ledger.OnChainBalance
import java.time.Instant

data class Balance(
    val accountId: AccountId,
    val currency: FiatCurrency,
    val amount: MonetaryAmount,
    val normalBalance: EntryType,
    val computedAt: Instant,
    val onChainBalances: List<OnChainBalance> = emptyList(),
)
