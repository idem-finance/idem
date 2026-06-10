package finance.idem.infrastructure.chain

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class QuickNodeWebhookPayload(
    @JsonProperty("signature") val signature: String = "",
    @JsonProperty("slot")      val slot: Long = 0L,
    @JsonProperty("network")   val network: String = "",
)
