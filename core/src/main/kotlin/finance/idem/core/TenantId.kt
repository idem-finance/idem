package finance.idem.core

import java.util.UUID

@JvmInline
value class TenantId(
    val value: UUID,
) {
    companion object {
        fun generate(): TenantId = TenantId(UUID.randomUUID())

        fun of(value: String): TenantId = TenantId(UUID.fromString(value))
    }
}
