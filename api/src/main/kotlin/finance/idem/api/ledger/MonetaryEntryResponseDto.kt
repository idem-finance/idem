package finance.idem.api.ledger

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import finance.idem.core.monetary.FiatEntry
import finance.idem.core.monetary.MonetaryEntry
import finance.idem.core.monetary.OnChainEntry

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = FiatEntryResponseDto::class, name = "FIAT"),
    JsonSubTypes.Type(value = OnChainEntryResponseDto::class, name = "ONCHAIN"),
)
sealed class MonetaryEntryResponseDto {
    companion object {
        fun from(entry: MonetaryEntry): MonetaryEntryResponseDto = when (entry) {
            is FiatEntry -> FiatEntryResponseDto.from(entry)
            is OnChainEntry -> OnChainEntryResponseDto.from(entry)
        }
    }
}
