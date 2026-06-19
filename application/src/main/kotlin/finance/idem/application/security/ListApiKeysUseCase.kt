package finance.idem.application.security

import finance.idem.core.TenantId
import finance.idem.core.security.ApiKey

interface ListApiKeysUseCase {
    fun execute(tenantId: TenantId): List<ApiKey>
}
