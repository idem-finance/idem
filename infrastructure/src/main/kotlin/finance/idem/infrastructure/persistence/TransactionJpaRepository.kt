package finance.idem.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface TransactionJpaRepository : JpaRepository<TransactionDataModel, UUID> {
    fun findByIdAndTenantId(
        id: UUID,
        tenantId: UUID,
    ): TransactionDataModel?

    fun findByIdempotencyKeyAndTenantId(
        idempotencyKey: String,
        tenantId: UUID,
    ): TransactionDataModel?

    @Query(
        """
        SELECT DISTINCT t FROM TransactionDataModel t
        JOIN t.lines l
        WHERE l.accountId = :accountId AND t.tenantId = :tenantId
    """,
    )
    fun findByAccountIdAndTenantId(
        @Param("accountId") accountId: UUID,
        @Param("tenantId") tenantId: UUID,
    ): List<TransactionDataModel>
}
