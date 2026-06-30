package finance.idem.infrastructure.persistence.policy

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PolicyRuleJpaRepository : JpaRepository<PolicyRuleDataModel, UUID> {
    fun findByIdAndTenantId(id: UUID, tenantId: UUID): PolicyRuleDataModel?
}
