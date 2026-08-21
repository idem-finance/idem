package finance.idem.infrastructure.persistence.reconciliation

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface SettlementJpaRepository : JpaRepository<SettlementDataModel, UUID> {
    // PESSIMISTIC_WRITE (SELECT ... FOR UPDATE) so two concurrent reconcile() calls
    // for the same wallet/token can't both match the same PENDING row — the second
    // transaction blocks until the first commits, then re-evaluates the WHERE
    // clause and no longer sees a row whose status has moved off PENDING.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        SELECT p FROM SettlementDataModel p
        WHERE p.tenantId = :tenantId
          AND p.accountId IN :accountIds
          AND p.token = :token
          AND p.chainId = :chainId
          AND p.walletAddress = :walletAddress
          AND p.status = 'PENDING'
          AND p.createdAt >= :since
        ORDER BY p.createdAt ASC
        """,
    )
    fun findPendingCandidates(
        @Param("tenantId") tenantId: UUID,
        @Param("accountIds") accountIds: Set<UUID>,
        @Param("token") token: String,
        @Param("chainId") chainId: String,
        @Param("walletAddress") walletAddress: String,
        @Param("since") since: Instant,
    ): List<SettlementDataModel>

    // PESSIMISTIC_WRITE so concurrent sweeps serialise at this point: the second sweep
    // blocks until the first commits all its per-group transactions, at which point
    // settled entries no longer match the UNMATCHED status filter.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        SELECT s FROM SettlementDataModel s
        WHERE s.tenantId = :tenantId
          AND s.status = 'UNMATCHED'
          AND s.createdAt >= :from
          AND s.createdAt < :to
          AND (:accountId IS NULL OR s.accountId = :accountId)
        ORDER BY s.createdAt ASC
        """,
    )
    fun findUnmatchedInWindow(
        @Param("tenantId") tenantId: UUID,
        @Param("accountId") accountId: UUID?,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<SettlementDataModel>

    // Nullable filter params wrapped in CAST(... AS type) so Postgres can resolve their
    // type when they appear only in an IS NULL context — same pattern as journal_lines findPage.
    @Query(
        value = """
            SELECT * FROM settlements s
            WHERE s.tenant_id = :tenantId
              AND (CAST(:status AS text) IS NULL OR s.status = CAST(:status AS text))
              AND (CAST(:from AS timestamptz) IS NULL OR s.created_at >= CAST(:from AS timestamptz))
              AND (CAST(:to AS timestamptz) IS NULL OR s.created_at <= CAST(:to AS timestamptz))
              AND (
                CAST(:afterCreatedAt AS timestamptz) IS NULL
                OR s.created_at < CAST(:afterCreatedAt AS timestamptz)
                OR (s.created_at = CAST(:afterCreatedAt AS timestamptz) AND s.id < CAST(:afterId AS uuid))
              )
            ORDER BY s.created_at DESC, s.id DESC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findPage(
        @Param("tenantId") tenantId: UUID,
        @Param("status") status: String?,
        @Param("from") from: Instant?,
        @Param("to") to: Instant?,
        @Param("afterCreatedAt") afterCreatedAt: Instant?,
        @Param("afterId") afterId: UUID?,
        @Param("limit") limit: Int,
    ): List<SettlementDataModel>

    // Plain read, not PESSIMISTIC_WRITE: ReorgReversalService's whole reversal runs inside a
    // single @Transactional, and the status <> 'REORGED' filter makes duplicate webhook
    // delivery idempotent without needing a row lock beyond normal read-committed isolation.
    @Query(
        """
        SELECT s FROM SettlementDataModel s
        WHERE s.tenantId = :tenantId AND s.txHash = :txHash AND s.logIndex = :logIndex
          AND s.matchedTransactionId IS NOT NULL AND s.status <> 'REORGED'
        ORDER BY s.createdAt DESC
        """,
    )
    fun findReversibleByTxHashAndLogIndex(
        @Param("tenantId") tenantId: UUID,
        @Param("txHash") txHash: String,
        @Param("logIndex") logIndex: Int,
    ): List<SettlementDataModel>

    // Tenant-scoped: settlements keeps FORCE ROW LEVEL SECURITY, so the adapter calls this
    // once per tenant (see SettlementRepositoryAdapter.findWatchingByChainKey) rather than
    // reading cross-tenant.
    @Query(
        """
        SELECT s FROM SettlementDataModel s
        WHERE s.tenantId = :tenantId AND s.chainKey = :chainKey
          AND s.status = 'WATCHING' AND s.blockNumber <= :upToBlock
        ORDER BY s.createdAt ASC
        """,
    )
    fun findWatchingByChainKey(
        @Param("tenantId") tenantId: UUID,
        @Param("chainKey") chainKey: String,
        @Param("upToBlock") upToBlock: Long,
    ): List<SettlementDataModel>
}
