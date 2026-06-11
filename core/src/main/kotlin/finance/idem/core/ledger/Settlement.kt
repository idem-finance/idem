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
)
