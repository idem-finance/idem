package finance.idem.core.security

import finance.idem.core.TenantId

data class ValidatedApiKey(
    val tenantId: TenantId,
    val scopes: Set<ApiScope>,
) {
    fun hasScope(scope: ApiScope): Boolean = scopes.contains(scope)
}
