package finance.idem.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface AccountJpaRepository : JpaRepository<AccountDataModel, UUID> {
    fun findAllByTenantId(tenantId: UUID): List<AccountDataModel>

    @Query("SELECT a.id FROM AccountDataModel a WHERE a.id IN :ids AND a.tenantId = :tenantId")
    fun findExistingIds(
        @Param("ids") ids: Collection<UUID>,
        @Param("tenantId") tenantId: UUID,
    ): Set<UUID>
}
