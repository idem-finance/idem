package finance.idem.sdk.model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = FiatEntryResponse::class, name = "FIAT"),
    JsonSubTypes.Type(value = OnChainEntryResponse::class, name = "ONCHAIN"),
)
sealed class MonetaryEntryResponse