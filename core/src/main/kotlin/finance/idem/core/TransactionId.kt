package finance.idem.core

import java.util.UUID

@JvmInline
value class TransactionId(val value: UUID) {
    companion object {
        fun generate(): TransactionId = TransactionId(UUID.randomUUID())
        fun of(value: String): TransactionId = TransactionId(UUID.fromString(value))
    }
}
