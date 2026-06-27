package finance.idem.infrastructure.persistence.audit

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface AuditLogJpaRepository : JpaRepository<AuditLogDataModel, UUID> {
    fun findByTenantIdAndOccurredAtBetween(tenantId: UUID, from: Instant, to: Instant): List<AuditLogDataModel>
}
