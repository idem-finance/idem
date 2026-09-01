package finance.idem.api.internal

import java.util.UUID

data class ProvisionTenantResponse(
    val tenantId: UUID,
    val apiKey: String,
    val dashboardUrl: String,
)
