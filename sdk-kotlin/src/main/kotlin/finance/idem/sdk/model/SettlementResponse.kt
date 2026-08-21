package finance.idem.sdk.model

import java.time.Instant
import java.util.UUID

/**
 * [status] is kept as the server's raw string (e.g. "PENDING", "WATCHING", "SETTLED",
 * "UNMATCHED", "CANCELLED", "REORGED") rather than a client-side enum, since the set of
 * statuses is server-owned and open to future additions.
 */
data class SettlementResponse(
    val settlementId: UUID,
    val accountId: UUID,
    val status: String,
    val expectedToken: String,
    val expectedAmount: String,
    val expectedWalletAddress: String,
    val expectedChainId: String,
    val expectedFromAddress: String?,
    val matchedTransactionId: UUID?,
    val txHash: String?,
    val blockNumber: Long?,
    val confirmedAt: Instant?,
    val expiresAt: Instant?,
    val createdAt: Instant,
    val chainKey: String?,
    val logIndex: Int?,
    val observedBlockHeight: Long?,
    val confirmationSource: String?,
    val reversalTransactionId: UUID?,
    val reorgedAt: Instant?,
)
