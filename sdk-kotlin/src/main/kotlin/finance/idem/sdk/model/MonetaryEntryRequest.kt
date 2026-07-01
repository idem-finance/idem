package finance.idem.sdk.model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = FiatEntryRequest::class, name = "FIAT"),
    JsonSubTypes.Type(value = OnChainEntryRequest::class, name = "ONCHAIN"),
)
sealed class MonetaryEntryRequest
