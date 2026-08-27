package finance.idem.core.ledger

import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import java.time.Instant
import java.util.UUID

interface SettlementRepository {
    fun save(settlement: Settlement): Settlement

    fun findById(
        id: UUID,
        tenantId: TenantId,
    ): Settlement?

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

    /** Paginated listing for a tenant, optionally filtered by status and/or time range.
     * Keyset cursor: rows where (createdAt < afterCreatedAt) OR (createdAt == afterCreatedAt AND id < afterId).
     * Ordered createdAt DESC, id DESC. */
    fun findPage(
        tenantId: TenantId,
        status: EntryStatus?,
        from: Instant?,
        to: Instant?,
        afterCreatedAt: Instant?,
        afterId: UUID?,
        limit: Int,
    ): List<Settlement>

    /** Most recent settlement matched to a posted transaction for this exact
     * (txHash, logIndex) — WATCHING/SETTLED/UNMATCHED all qualify (all three have a real
     * posted ledger Transaction that must be compensated on reorg). Excludes rows already
     * REORGED, so re-delivery of the same removed:true webhook is a no-op. Null if no match. */
    fun findReversibleByTxHashAndLogIndex(
        tenantId: TenantId,
        txHash: String,
        logIndex: Int,
    ): Settlement?

    /** Most recent REORGED settlement for this exact (txHash, logIndex), if any — used to
     * distinguish a genuine chain re-mine (new blockNumber) from a stale at-least-once webhook
     * redelivery of the exact evidence that was just reversed (identical blockNumber). Null if
     * this (txHash, logIndex) has never been reversed. */
    fun findReorgedByTxHashAndLogIndex(
        tenantId: TenantId,
        txHash: String,
        logIndex: Int,
    ): Settlement?

    /** WATCHING and webhook-sourced UNMATCHED rows for tenant+chainKey, not yet
     * finality-confirmed (confirmedAt IS NULL) and with blockNumber <= upToBlock — candidates
     * for the finality-sweep poller. Tenant-scoped (not a cross-tenant query): `settlements`
     * keeps FORCE ROW LEVEL SECURITY, unlike webhook_outbox, so the poller derives its tenant
     * set from WatchedAddressRepository.findByChainKey (already cross-tenant, no RLS) and calls
     * this once per tenant. */
    fun findPendingFinalitySweep(
        tenantId: TenantId,
        chainKey: String,
        upToBlock: Long,
    ): List<Settlement>

    /** Settlements for this account matching the given status — used to report the
     * pending-finality portion of an on-chain balance (status = WATCHING) without exposing it
     * as confirmed. Not paginated: callers use this for small, bounded per-account/status sets. */
    fun findByAccountIdAndStatus(
        tenantId: TenantId,
        accountId: AccountId,
        status: EntryStatus,
    ): List<Settlement>

    /** Atomically transitions a settlement to REORGED — a conditional update
     * (`WHERE status <> 'REORGED'`), not a plain save, so two concurrent reorg-detection paths
     * (webhook fast path + poller backstop) racing the same settlement result in exactly one
     * transition. Returns whether this call performed the transition. */
    fun markReorged(
        id: UUID,
        tenantId: TenantId,
        reversalTransactionId: TransactionId,
        reorgedAt: Instant,
    ): Boolean
}
