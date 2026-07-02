package finance.idem.infrastructure.persistence.audit

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface AgentAuditEventJpaRepository : JpaRepository<AgentAuditEventDataModel, UUID> {
    @Query("SELECT e FROM AgentAuditEventDataModel e WHERE e.tenantId = :tenantId AND e.occurredAt >= :from AND e.occurredAt < :to")
    fun findForExport(
        @Param("tenantId") tenantId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<AgentAuditEventDataModel>
}
