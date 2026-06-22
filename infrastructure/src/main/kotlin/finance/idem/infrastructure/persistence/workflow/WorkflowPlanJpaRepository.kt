package finance.idem.infrastructure.persistence.workflow

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface WorkflowPlanJpaRepository : JpaRepository<WorkflowPlanDataModel, UUID> {
    fun findByIdAndTenantId(id: UUID, tenantId: UUID): WorkflowPlanDataModel?
}
