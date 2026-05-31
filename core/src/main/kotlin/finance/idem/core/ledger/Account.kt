package finance.idem.core.ledger

import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.TenantId
import java.time.Instant

data class Account internal constructor(
    val id: AccountId,
    val tenantId: TenantId,
    val name: String,
    val description: String? = null,
    val currency: FiatCurrency,
    val type: AccountType,
    val normalBalance: EntryType,
    val createdAt: Instant,
    val createdBy: String,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
) {
    companion object {
        fun create(
            id: AccountId,
            tenantId: TenantId,
            name: String,
            currency: FiatCurrency,
            type: AccountType,
            createdAt: Instant,
            createdBy: String,
            description: String? = null,
        ): Account = Account(
            id = id,
            tenantId = tenantId,
            name = name,
            description = description,
            currency = currency,
            type = type,
            normalBalance = type.normalBalance(),
            createdAt = createdAt,
            createdBy = createdBy,
        )
    }
}
