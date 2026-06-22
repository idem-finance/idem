package finance.idem.infrastructure.persistence.audit

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "agent_audit_events")
class AgentAuditEventDataModel(
    @Id
    val id: UUID,

    @Column(name = "workflow_plan_id", nullable = false)
    val workflowPlanId: UUID,

    @Column(name = "tenant_id", nullable = false)
    val tenantId: UUID,

    @Column(name = "agent_id", nullable = false)
    val agentId: String,

    @Column(name = "session_id", nullable = false)
    val sessionId: String,

    val intent: String?,

    @Column(nullable = false)
    val status: String,

    val outcome: String?,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    val payload: String,

    @Column(nullable = false)
    val hmac: String,

    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
) {
    protected constructor() : this(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        "", "", null, "PENDING", null, "{}", "", Instant.now(),
    )
}
