package finance.idem.infrastructure.chain

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class QuickNodeWebhookPayload(
    @JsonProperty("signature") val signature: String = "",
    @JsonProperty("slot") val slot: Long = 0L,
    @JsonProperty("network") val network: String = "",
)

// QuickNode Streams always wraps deliveries in a {data, metadata} envelope, even when a
// custom filter function reshapes `data` — see https://www.quicknode.com/docs/streams/data-sources
@JsonIgnoreProperties(ignoreUnknown = true)
data class QuickNodeStreamPayload(
    @JsonProperty("data") val data: List<QuickNodeWebhookPayload> = emptyList(),
    @JsonProperty("metadata") val metadata: QuickNodeStreamMetadata = QuickNodeStreamMetadata(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class QuickNodeStreamMetadata(
    @JsonProperty("streamId") val streamId: String = "",
)
