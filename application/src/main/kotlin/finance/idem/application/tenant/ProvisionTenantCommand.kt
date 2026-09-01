package finance.idem.application.tenant

import finance.idem.core.tenant.TenantPlan

data class ProvisionTenantCommand(
    val adminToken: String?,
    val organizationName: String,
    val contactEmail: String,
    val plan: TenantPlan,
)
