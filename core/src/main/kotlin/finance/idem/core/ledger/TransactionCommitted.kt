package finance.idem.core.ledger

import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import java.time.Instant

data class TransactionCommitted(
    val transactionId: TransactionId,
    val tenantId: TenantId,
    val occurredAt: Instant,
    val lineCount: Int,
)
