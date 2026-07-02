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
    val createdAt: Instant,
    val createdBy: String,
    val updatedAt: Instant? = null,
    val updatedBy: String? = null,
) {
    // Computed — not a constructor parameter, so copy(normalBalance=...) cannot compile.
    // Changing type via copy() automatically re-derives the correct normal balance.
    val normalBalance: EntryType get() = type.normalBalance()

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
        ): Account =
            Account(
                id = id,
                tenantId = tenantId,
                name = name,
                description = description,
                currency = currency,
                type = type,
                createdAt = createdAt,
                createdBy = createdBy,
            )

        /** Rebuilds an Account from persisted data — skips creation-time validation. */
        fun reconstitute(
            id: AccountId,
            tenantId: TenantId,
            name: String,
            currency: FiatCurrency,
            type: AccountType,
            createdAt: Instant,
            createdBy: String,
            description: String? = null,
            updatedAt: Instant? = null,
            updatedBy: String? = null,
        ): Account =
            Account(
                id = id,
                tenantId = tenantId,
                name = name,
                description = description,
                currency = currency,
                type = type,
                createdAt = createdAt,
                createdBy = createdBy,
                updatedAt = updatedAt,
                updatedBy = updatedBy,
            )
    }
}
