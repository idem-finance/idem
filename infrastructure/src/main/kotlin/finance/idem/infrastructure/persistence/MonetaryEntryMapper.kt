package finance.idem.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import finance.idem.core.ChainId
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.StablecoinToken
import finance.idem.core.monetary.FiatEntry
import finance.idem.core.monetary.MonetaryEntry
import finance.idem.core.monetary.OnChainEntry
import java.math.BigDecimal

private const val TYPE_FIAT    = "FIAT"
private const val TYPE_ONCHAIN = "ONCHAIN"

data class MonetaryEntryColumns(
    val amount: BigDecimal,
    val currency: String,
    val monetaryEntryType: String,
    val monetaryEntryData: String,
)

fun MonetaryEntry.toColumns(mapper: ObjectMapper): MonetaryEntryColumns = when (this) {
    is FiatEntry -> MonetaryEntryColumns(
        amount = amount.value,
        currency = currency.name,
        monetaryEntryType = TYPE_FIAT,
        monetaryEntryData = mapper.writeValueAsString(mapOf(
            "rail" to rail.name,
            "bankReference" to bankReference,
        )),
    )
    is OnChainEntry -> MonetaryEntryColumns(
        amount = amount.value,
        currency = token.name,
        monetaryEntryType = TYPE_ONCHAIN,
        monetaryEntryData = mapper.writeValueAsString(mapOf(
            "chainId" to chainId.name,
            "txHash" to txHash,
            "blockNumber" to blockNumber,
            "walletAddress" to walletAddress,
            "tokenContract" to tokenContract,
        )),
    )
}

fun MonetaryEntryColumns.toDomain(mapper: ObjectMapper): MonetaryEntry {
    val monetaryAmount = MonetaryAmount.of(amount)
    return when (monetaryEntryType) {
        TYPE_FIAT -> {
            val data: Map<String, String?> = mapper.readValue(monetaryEntryData)
            FiatEntry(
                amount = monetaryAmount,
                currency = FiatCurrency.valueOf(currency),
                rail = PaymentRail.valueOf(data["rail"]!!),
                bankReference = data["bankReference"],
            )
        }
        TYPE_ONCHAIN -> {
            val data: Map<String, Any?> = mapper.readValue(monetaryEntryData)
            OnChainEntry(
                amount = monetaryAmount,
                token = StablecoinToken.valueOf(currency),
                chainId = ChainId.valueOf(data["chainId"] as String),
                txHash = data["txHash"] as String,
                blockNumber = (data["blockNumber"] as Number).toLong(),
                walletAddress = data["walletAddress"] as String,
                tokenContract = data["tokenContract"] as String,
            )
        }
        else -> error("Unknown monetary_entry_type: $monetaryEntryType")
    }
}
