package finance.idem.application.tenant

import finance.idem.core.TenantId

data class ProvisionedTenant(
    val tenantId: TenantId,
    val rawApiKey: String,
    val dashboardUrl: String,
)
