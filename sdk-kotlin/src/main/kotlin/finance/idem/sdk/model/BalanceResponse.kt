package finance.idem.sdk.model

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class BalanceResponse(
    val accountId: UUID,
    val currency: FiatCurrency,
    val amount: BigDecimal,
    val normalBalance: EntryType,
    val computedAt: Instant,
)