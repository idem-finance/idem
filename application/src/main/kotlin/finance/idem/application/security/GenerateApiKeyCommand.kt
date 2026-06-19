package finance.idem.application.security

import finance.idem.core.TenantId
import finance.idem.core.security.ApiScope

data class GenerateApiKeyCommand(
    val tenantId: TenantId,
    val requestedScopes: Set<ApiScope>,
    val callerScopes: Set<ApiScope>,
)
