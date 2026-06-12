package finance.idem.application.port

import finance.idem.application.tenant.TenantWebhookConfig
import finance.idem.core.TenantId

interface TenantRepository {
    /**
     * Returns this tenant's webhook delivery config, or `null` if the tenant
     * has not configured one yet (no row, or `webhook_url`/`webhook_secret`
     * is null/blank). Callers must treat `null` as "not yet configured", not
     * as an error.
     */
    fun findWebhookConfig(tenantId: TenantId): TenantWebhookConfig?
}
