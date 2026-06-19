package finance.idem.application.ledger

import finance.idem.core.FiatCurrency
import finance.idem.core.TenantId
import finance.idem.core.ledger.AccountType

data class CreateAccountCommand(
    val tenantId: TenantId,
    val name: String,
    val description: String?,
    val currency: FiatCurrency,
    val type: AccountType,
    val createdBy: String,
)
