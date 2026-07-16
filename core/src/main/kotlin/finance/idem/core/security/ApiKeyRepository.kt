package finance.idem.core.security

import finance.idem.core.TenantId

interface ApiKeyRepository {
    fun save(apiKey: ApiKey): ApiKey

    /**
     * Returns every key sharing this prefix. The prefix (first 12 chars of the raw
     * key) is not unique — its index is non-unique by design — so callers must
     * disambiguate by bcrypt-matching the raw key against each candidate's hash.
     */
    fun findAllByPrefix(prefix: String): List<ApiKey>

    fun findById(
        id: ApiKeyId,
        tenantId: TenantId,
    ): ApiKey?

    fun findAllByTenantId(tenantId: TenantId): List<ApiKey>
}
