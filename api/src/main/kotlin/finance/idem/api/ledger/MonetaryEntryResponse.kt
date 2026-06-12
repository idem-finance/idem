package finance.idem.api.ledger

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import finance.idem.core.monetary.FiatEntry
import finance.idem.core.monetary.MonetaryEntry
import finance.idem.core.monetary.OnChainEntry

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = FiatEntryResponse::class, name = "FIAT"),
    JsonSubTypes.Type(value = OnChainEntryResponse::class, name = "ONCHAIN"),
)
sealed class MonetaryEntryResponse {
    companion object {
        fun from(entry: MonetaryEntry): MonetaryEntryResponse = when (entry) {
            is FiatEntry -> FiatEntryResponse.from(entry)
            is OnChainEntry -> OnChainEntryResponse.from(entry)
        }
    }
}
