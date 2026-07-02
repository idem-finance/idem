package finance.idem.api.ledger

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

data class CreateAccountRequest(
    @field:NotBlank
    @Schema(description = "Account display name")
    val name: String,
    @Schema(description = "Optional description")
    val description: String? = null,
    @field:NotBlank
    @Schema(description = "ISO 4217 currency code: BRL, USD, MXN, EUR")
    val currency: String,
    @field:NotBlank
    @Schema(description = "Account type: ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE")
    val type: String,
)
