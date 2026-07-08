package finance.idem.sdk.model

import java.time.Instant
import java.util.UUID

data class CreateAccountResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val currency: FiatCurrency,
    val type: AccountType,
    val normalBalance: EntryType,
    val createdAt: Instant,
)
