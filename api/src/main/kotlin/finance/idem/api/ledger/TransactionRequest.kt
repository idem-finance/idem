package finance.idem.api.ledger

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import finance.idem.application.ledger.JournalLineRequest
import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.monetary.MonetaryEntry
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.util.UUID

data class PostTransactionRequest(
    @Schema(description = "Journal lines — must be balanced (debits == credits per currency)", minLength = 2)
    val lines: List<JournalLineRequestDto>,
    @Schema(description = "Arbitrary key-value metadata attached to the transaction")
    val metadata: Map<String, String> = emptyMap(),
) {
    fun toCommand(tenantId: TenantId, idempotencyKey: String): PostTransactionCommand =
        PostTransactionCommand(
            tenantId = tenantId,
            idempotencyKey = idempotencyKey,
            lines = lines.map { it.toDomain() },
            createdBy = "api",
            metadata = metadata,
        )
}

data class JournalLineRequestDto(
    @Schema(description = "Account UUID", required = true)
    val accountId: UUID,
    @Schema(description = "DEBIT or CREDIT", required = true)
    val entryType: EntryType,
    @Schema(description = "Monetary entry — FIAT or ONCHAIN", required = true)
    val monetaryEntry: MonetaryEntryRequestDto,
    val description: String? = null,
) {
    fun toDomain() = JournalLineRequest(
        accountId = AccountId(accountId),
        entryType = entryType,
        monetaryEntry = monetaryEntry.toDomain(),
        description = description,
    )
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = MonetaryEntryRequestDto.FiatEntryDto::class, name = "FIAT"),
    JsonSubTypes.Type(value = MonetaryEntryRequestDto.OnChainEntryDto::class, name = "ONCHAIN"),
)
sealed class MonetaryEntryRequestDto {
    abstract fun toDomain(): MonetaryEntry

    data class FiatEntryDto(
        val amount: BigDecimal,
        val currency: FiatCurrency,
        val rail: PaymentRail,
        val bankReference: String? = null,
    ) : MonetaryEntryRequestDto() {
        override fun toDomain() = MonetaryEntry.FiatEntry(
            amount = MonetaryAmount.of(amount),
            currency = currency,
            rail = rail,
            bankReference = bankReference,
        )
    }

    data class OnChainEntryDto(
        val amount: BigDecimal,
        val token: StablecoinToken,
        val chainId: ChainId,
        val txHash: String,
        val blockNumber: Long,
        val walletAddress: String,
        val tokenContract: String,
    ) : MonetaryEntryRequestDto() {
        override fun toDomain() = MonetaryEntry.OnChainEntry(
            amount = MonetaryAmount.of(amount),
            token = token,
            chainId = chainId,
            txHash = txHash,
            blockNumber = blockNumber,
            walletAddress = walletAddress,
            tokenContract = tokenContract,
        )
    }
}
