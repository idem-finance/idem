package finance.idem.core.ledger

import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import java.time.Instant
import java.util.UUID

data class Settlement(
    val id: UUID,
    val tenantId: TenantId,
    val accountId: AccountId,
    val amount: MonetaryAmount,
    val token: StablecoinToken,
    val chainId: ChainId,
    val walletAddress: String,
    val status: EntryStatus,
    val matchedTransactionId: TransactionId? = null,
    val txHash: String? = null,
    val blockNumber: Long? = null,
    val confirmedAt: Instant? = null,
    val expectedFromAddress: String? = null,
    val createdAt: Instant,
    val createdBy: String,
    // Chain-finality evidence — populated once a chain-sourced match is recorded (see
    // BasicReconciliationService.settle()). chainKey/logIndex identify the specific on-chain
    // log a webhook-sourced match came from (a single txHash can carry multiple independent
    // watched-address transfers, disambiguated by logIndex).
    val chainKey: String? = null,
    val logIndex: Int? = null,
    val observedBlockHeight: Long? = null,
    val confirmationSource: String? = null,
    val confirmationsRequired: Long? = null,
    // Reorg-reversal marker — additive only. Set when a chain reorg invalidates a previously
    // matched/settled entry; the fields above (txHash/blockNumber/confirmedAt/etc.) are never
    // rewritten, preserving the original evidence for audit purposes.
    val reversalTransactionId: TransactionId? = null,
    val reorgedAt: Instant? = null,
)
