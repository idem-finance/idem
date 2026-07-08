package finance.idem.sdk.model

import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import java.math.BigDecimal
import java.util.UUID

/**
 * [expectedAmount] is serialized as a JSON string (not a bare number) — the server's
 * RegisterSettlementRequest.expectedAmount field is String-typed, unlike PostTransactionRequest's
 * numeric monetary amounts.
 */
data class RegisterSettlementRequest(
    val accountId: UUID,
    val expectedToken: StablecoinToken,
    @JsonSerialize(using = ToStringSerializer::class)
    val expectedAmount: BigDecimal,
    val expectedWalletAddress: String,
    val expectedChainId: ChainId,
    val expectedFromAddress: String? = null,
)
