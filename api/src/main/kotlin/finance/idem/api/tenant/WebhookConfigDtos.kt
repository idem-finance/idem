package finance.idem.api.tenant

import jakarta.validation.constraints.NotBlank

data class UpdateWebhookConfigRequest(
    @field:NotBlank(message = "webhookUrl must not be blank")
    val webhookUrl: String = "",
)

data class WebhookConfigCreatedResponse(
    val webhookUrl: String,
    val webhookSecret: String,
)

data class WebhookConfigResponse(
    val webhookUrl: String,
    val secretPrefix: String,
)
