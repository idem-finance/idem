package finance.idem.application.security

import finance.idem.core.TenantId
import finance.idem.core.security.ApiKeyId

interface RevokeApiKeyUseCase {
    /** Returns false when the key was not found for this tenant. */
    fun execute(
        keyId: ApiKeyId,
        tenantId: TenantId,
    ): Boolean
}
