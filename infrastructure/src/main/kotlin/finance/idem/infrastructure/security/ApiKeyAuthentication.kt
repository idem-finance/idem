package finance.idem.infrastructure.security

import finance.idem.core.TenantId
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority

class ApiKeyAuthentication(
    val tenantId: TenantId,
    val keyPrefix: String,
    authorities: Collection<GrantedAuthority>,
) : AbstractAuthenticationToken(authorities) {
    init { isAuthenticated = true }
    override fun getCredentials() = null
    override fun getPrincipal() = tenantId
    override fun getName() = keyPrefix
}
