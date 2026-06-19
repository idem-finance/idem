package finance.idem.api.security

import finance.idem.core.security.ApiKey
import java.time.Instant
import java.util.UUID

data class ApiKeyResponse(
    val id: UUID,
    val prefix: String,
    val scopes: List<String>,
    val createdAt: Instant,
    val revokedAt: Instant?,
) {
    companion object {
        fun from(key: ApiKey) = ApiKeyResponse(
            id = key.id.value,
            prefix = key.prefix,
            scopes = key.scopes.map { it.name },
            createdAt = key.createdAt,
            revokedAt = key.revokedAt,
        )
    }
}

data class CreateApiKeyResponse(
    val id: UUID,
    val rawKey: String,
    val prefix: String,
    val scopes: List<String>,
    val createdAt: Instant,
)
