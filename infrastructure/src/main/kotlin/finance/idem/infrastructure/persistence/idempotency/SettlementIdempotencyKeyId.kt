package finance.idem.infrastructure.persistence.idempotency

import java.io.Serializable
import java.util.UUID

data class SettlementIdempotencyKeyId(
    val tenantId: UUID = UUID.randomUUID(),
    val key: String = "",
) : Serializable
