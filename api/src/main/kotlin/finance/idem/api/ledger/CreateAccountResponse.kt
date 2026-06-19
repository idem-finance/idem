package finance.idem.api.ledger

import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountType
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

data class CreateAccountResponse(
    @Schema(description = "Account UUID")
    val id: UUID,
    val name: String,
    val description: String?,
    val currency: FiatCurrency,
    val type: AccountType,
    @Schema(description = "DEBIT for asset/expense accounts, CREDIT for liability/equity/revenue")
    val normalBalance: EntryType,
    val createdAt: Instant,
) {
    companion object {
        fun from(account: Account) = CreateAccountResponse(
            id = account.id.value,
            name = account.name,
            description = account.description,
            currency = account.currency,
            type = account.type,
            normalBalance = account.normalBalance,
            createdAt = account.createdAt,
        )
    }
}
