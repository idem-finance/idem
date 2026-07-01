package finance.idem.core.security

import finance.idem.core.TenantId
import java.time.Instant

data class ApiKey(
    val id: ApiKeyId,
    val tenantId: TenantId,
    val keyHash: String,
    val prefix: String,
    val scopes: Set<ApiScope>,
    val createdAt: Instant,
    val revokedAt: Instant? = null,
) {
    val isRevoked: Boolean get() = revokedAt != null

    fun hasScope(scope: ApiScope): Boolean = scopes.contains(scope)

    companion object {
        fun create(
            tenantId: TenantId,
            keyHash: String,
            prefix: String,
            scopes: Set<ApiScope>,
            createdAt: Instant = Instant.now(),
        ): ApiKey =
            ApiKey(
                id = ApiKeyId.generate(),
                tenantId = tenantId,
                keyHash = keyHash,
                prefix = prefix,
                scopes = scopes,
                createdAt = createdAt,
            )
    }
}
