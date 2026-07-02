package finance.idem.application.tenant

import finance.idem.core.TenantId

interface UpdateWebhookConfigUseCase {
    /** Validates [webhookUrl], generates a signing secret, persists config, and returns it. */
    fun execute(
        tenantId: TenantId,
        webhookUrl: String,
    ): Result<TenantWebhookConfig>
}
