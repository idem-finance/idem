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
        """
    )
    fun findPendingCandidates(
        @Param("tenantId") tenantId: UUID,
        @Param("accountIds") accountIds: Set<UUID>,
        @Param("token") token: String,
        @Param("chainId") chainId: String,
        @Param("walletAddress") walletAddress: String,
        @Param("since") since: Instant,
    ): List<SettlementDataModel>
}
