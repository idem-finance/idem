package finance.idem.application.tenant

import finance.idem.core.TenantId

interface GetWebhookConfigUseCase {
    fun execute(tenantId: TenantId): TenantWebhookConfig?
}
