package finance.idem.infrastructure.compliance

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TravelRuleDataJpaRepository : JpaRepository<TravelRuleDataDataModel, UUID> {
    fun findByTransferIdAndTenantId(transferId: String, tenantId: UUID): TravelRuleDataDataModel?
}
