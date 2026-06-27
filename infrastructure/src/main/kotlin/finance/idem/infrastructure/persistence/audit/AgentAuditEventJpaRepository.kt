package finance.idem.infrastructure.persistence.audit

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface AgentAuditEventJpaRepository : JpaRepository<AgentAuditEventDataModel, UUID> {
    fun findByTenantIdAndOccurredAtBetween(tenantId: UUID, from: Instant, to: Instant): List<AgentAuditEventDataModel>
}
