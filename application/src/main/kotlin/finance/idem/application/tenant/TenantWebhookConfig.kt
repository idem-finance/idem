package finance.idem.application.tenant

data class TenantWebhookConfig(
    val webhookUrl: String,
    val webhookSecret: String,
)
