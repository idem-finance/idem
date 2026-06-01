package finance.idem.infrastructure.persistence.idempotency

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface IdempotencyKeyJpaRepository : JpaRepository<IdempotencyKeyDataModel, UUID> {

    @Query("""
        SELECT i FROM IdempotencyKeyDataModel i
        WHERE i.idempotencyKey = :key
          AND i.tenantId = :tenantId
          AND i.expiresAt > :now
    """)
    fun findActiveByKeyAndTenantId(
        @Param("key") key: String,
        @Param("tenantId") tenantId: UUID,
        @Param("now") now: Instant,
    ): IdempotencyKeyDataModel?

    fun deleteByIdempotencyKeyAndTenantId(idempotencyKey: String, tenantId: UUID)
}
