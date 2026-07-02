package finance.idem.sdk.model

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class StatementResponse(
    val accountId: UUID,
    val currency: FiatCurrency,
    val from: Instant,
    val to: Instant,
    val openingBalance: BigDecimal,
    val closingBalance: BigDecimal,
    val movements: List<StatementMovementResponse>,
)
