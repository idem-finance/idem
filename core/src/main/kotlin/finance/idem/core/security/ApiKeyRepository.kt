package finance.idem.core.security

import finance.idem.core.TenantId

interface ApiKeyRepository {
    fun save(apiKey: ApiKey): ApiKey

    fun findByPrefix(prefix: String): ApiKey?

    fun findById(
        id: ApiKeyId,
        tenantId: TenantId,
    ): ApiKey?

    fun findAllByTenantId(tenantId: TenantId): List<ApiKey>
}
