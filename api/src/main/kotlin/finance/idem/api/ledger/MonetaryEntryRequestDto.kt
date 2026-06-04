package finance.idem.api.ledger

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import finance.idem.core.monetary.MonetaryEntry

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = FiatEntryDto::class, name = "FIAT"),
    JsonSubTypes.Type(value = OnChainEntryDto::class, name = "ONCHAIN"),
)
sealed class MonetaryEntryRequestDto {
    abstract fun toDomain(): MonetaryEntry
}
