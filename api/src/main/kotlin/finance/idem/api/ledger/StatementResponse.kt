package finance.idem.api.ledger

import finance.idem.application.ledger.AccountStatement
import finance.idem.core.FiatCurrency
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class StatementResponse(
    @Schema(description = "Account UUID")
    val accountId: UUID,
    @Schema(description = "ISO 4217 currency code")
    val currency: FiatCurrency,
    @Schema(description = "Inclusive lower bound of the statement period")
    val from: Instant,
    @Schema(description = "Inclusive upper bound of the statement period")
    val to: Instant,
    @Schema(description = "Account balance as of 'from'")
    val openingBalance: BigDecimal,
    @Schema(description = "Account balance as of 'to'")
    val closingBalance: BigDecimal,
    @Schema(description = "Journal line movements with occurredAt in (from, to], ordered ascending")
    val movements: List<StatementMovementResponse>,
) {
    companion object {
        fun from(statement: AccountStatement) =
            StatementResponse(
                accountId = statement.accountId.value,
                currency = statement.currency,
                from = statement.from,
                to = statement.to,
                openingBalance = statement.openingBalance.value,
                closingBalance = statement.closingBalance.value,
                movements = statement.movements.map { StatementMovementResponse.from(it) },
            )
    }
}
