package finance.idem.infrastructure.persistence.idempotency

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface IdempotencyKeyJpaRepository : JpaRepository<IdempotencyKeyDataModel, IdempotencyKeyId> {
    @Query(
        """
        SELECT i FROM IdempotencyKeyDataModel i
        WHERE i.key = :key
          AND i.tenantId = :tenantId
          AND i.expiresAt > CURRENT_TIMESTAMP
    """,
    )
    fun findActiveByKeyAndTenantId(
        @Param("key") key: String,
        @Param("tenantId") tenantId: UUID,
    ): IdempotencyKeyDataModel?

    fun deleteByKeyAndTenantId(
        key: String,
        tenantId: UUID,
    )
}
