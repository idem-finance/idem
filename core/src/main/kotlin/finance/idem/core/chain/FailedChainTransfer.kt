package finance.idem.core.chain

import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import java.time.Instant
import java.util.UUID

/**
 * Dead-letter record for an on-chain transfer whose transaction-posting use case returned
 * `Result.failure`. The chain checkpoint advances past these regardless, so this record is
 * the only durable trace an operator has to detect and manually correct the dropped entry.
 */
data class FailedChainTransfer(
    val id: UUID,
    val chainKey: String,
    val source: String,
    val idempotencyKey: String,
    val txHash: String,
    val blockNumber: Long,
    val tenantId: TenantId,
    val walletAddress: String,
    val tokenContract: String,
    val debitAccountId: UUID,
    val creditAccountId: UUID,
    val token: StablecoinToken,
    val amount: MonetaryAmount,
    val errorMessage: String,
    val createdAt: Instant,
)
