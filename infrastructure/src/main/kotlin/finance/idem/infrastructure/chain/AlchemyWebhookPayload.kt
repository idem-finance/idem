package finance.idem.infrastructure.chain

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class AlchemyWebhookPayload(
    @JsonProperty("webhookId") val webhookId: String = "",
    @JsonProperty("id") val id: String = "",
    @JsonProperty("createdAt") val createdAt: String = "",
    @JsonProperty("type") val type: String = "",
    @JsonProperty("event") val event: AlchemyWebhookEvent = AlchemyWebhookEvent(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AlchemyWebhookEvent(
    @JsonProperty("network") val network: String = "",
    @JsonProperty("activity") val activity: List<AlchemyActivity> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AlchemyActivity(
    @JsonProperty("fromAddress") val fromAddress: String = "",
    @JsonProperty("toAddress") val toAddress: String = "",
    @JsonProperty("blockNum") val blockNum: String = "",
    @JsonProperty("hash") val hash: String = "",
    @JsonProperty("value") val value: Double? = null,
    @JsonProperty("asset") val asset: String? = null,
    @JsonProperty("category") val category: String? = null,
    @JsonProperty("rawContract") val rawContract: AlchemyRawContract? = null,
    @JsonProperty("log") val log: AlchemyLog? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AlchemyRawContract(
    @JsonProperty("rawValue") val rawValue: String? = null,
    @JsonProperty("address") val address: String? = null,
    @JsonProperty("decimals") val decimals: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AlchemyLog(
    @JsonProperty("logIndex") val logIndex: String = "0x0",
    @JsonProperty("transactionHash") val transactionHash: String = "",
    @JsonProperty("blockNumber") val blockNumber: String = "",
    @JsonProperty("address") val address: String = "",
    @JsonProperty("data") val data: String = "",
    @JsonProperty("topics") val topics: List<String> = emptyList(),
    @JsonProperty("removed") val removed: Boolean = false,
)
