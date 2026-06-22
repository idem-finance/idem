package finance.idem.infrastructure.persistence.audit

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AgentAuditEventJpaRepository : JpaRepository<AgentAuditEventDataModel, UUID>
