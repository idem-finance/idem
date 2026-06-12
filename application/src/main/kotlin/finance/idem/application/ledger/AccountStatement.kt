package finance.idem.application.ledger

import finance.idem.core.AccountId
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import java.time.Instant

data class AccountStatement(
    val accountId: AccountId,
    val currency: FiatCurrency,
    val from: Instant,
    val to: Instant,
    val openingBalance: MonetaryAmount,
    val closingBalance: MonetaryAmount,
    val movements: List<StatementMovement>,
)
