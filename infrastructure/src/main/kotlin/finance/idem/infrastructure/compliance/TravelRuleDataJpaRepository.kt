package finance.idem.infrastructure.compliance

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface TravelRuleDataJpaRepository : JpaRepository<TravelRuleDataDataModel, UUID> {
    fun findByTransferIdAndTenantId(
        transferId: String,
        tenantId: UUID,
    ): TravelRuleDataDataModel?

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM TravelRuleDataDataModel t WHERE t.transferId = :transferId AND t.tenantId = :tenantId")
    fun deleteByTransferIdAndTenantId(
        @Param("transferId") transferId: String,
        @Param("tenantId") tenantId: UUID,
    ): Int
}
