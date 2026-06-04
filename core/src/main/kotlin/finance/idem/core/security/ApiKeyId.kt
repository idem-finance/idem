package finance.idem.core.security

import java.util.UUID

@JvmInline
value class ApiKeyId(val value: UUID) {
    companion object {
        fun generate(): ApiKeyId = ApiKeyId(UUID.randomUUID())
        fun of(value: String): ApiKeyId = ApiKeyId(UUID.fromString(value))
    }
}
