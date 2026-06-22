package finance.idem.core.ledger

import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import java.time.Instant
import java.util.UUID

interface SettlementRepository {
    fun save(settlement: Settlement): Settlement

    fun findById(id: UUID, tenantId: TenantId): Settlement?

    /** PENDING rows for tenant where accountId ∈ accountIds, matching
     * token/chainId/walletAddress, createdAt >= since. Ordered createdAt ASC. */
    fun findPendingCandidates(
        tenantId: TenantId,
        accountIds: Set<AccountId>,
        token: StablecoinToken,
        chainId: ChainId,
        walletAddress: String,
        since: Instant,
    ): List<Settlement>

    /** UNMATCHED rows for tenant in [from, to), optionally filtered by accountId.
     * Ordered createdAt ASC. */
    fun findUnmatchedInWindow(
        tenantId: TenantId,
        accountId: AccountId?,
        from: Instant,
        to: Instant,
    ): List<Settlement>
}
