package finance.idem.core

import java.util.UUID

@JvmInline
value class AccountId(val value: UUID) {
    companion object {
        fun generate(): AccountId = AccountId(UUID.randomUUID())
        fun of(value: String): AccountId = AccountId(UUID.fromString(value))
    }
}
