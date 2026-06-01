package finance.idem.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface AccountJpaRepository : JpaRepository<AccountJpaEntity, UUID> {
    fun findAllByTenantId(tenantId: UUID): List<AccountJpaEntity>

    @Query("SELECT a.id FROM AccountJpaEntity a WHERE a.id IN :ids AND a.tenantId = :tenantId")
    fun findExistingIds(@Param("ids") ids: Collection<UUID>, @Param("tenantId") tenantId: UUID): Set<UUID>
}
