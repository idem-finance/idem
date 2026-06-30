package finance.idem.api.settlement

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.util.UUID

data class RegisterSettlementRequest(
    @field:NotNull
    @Schema(description = "Account that will receive the on-chain transfer", required = true)
    val accountId: UUID,

    @field:NotBlank
    @Schema(description = "Expected stablecoin token (e.g. USDC, USDT)", required = true)
    val expectedToken: String,

    @field:NotBlank
    @Schema(description = "Expected transfer amount (e.g. \"100.00\")", required = true)
    val expectedAmount: String,

    @field:NotBlank
    @Schema(description = "Wallet address to watch for the incoming transfer", required = true)
    val expectedWalletAddress: String,

    @field:NotBlank
    @Schema(description = "Chain ID (EVM, SOLANA, TRON)", required = true)
    val expectedChainId: String,

    @Schema(description = "Optional: expected sender address for sender-confirmed matching")
    val expectedFromAddress: String? = null,

    @Schema(description = "Match window in hours; if omitted the tenant default applies. Used only to compute expiresAt in the response — not persisted.")
    val matchWindowHours: Long? = null,
)
