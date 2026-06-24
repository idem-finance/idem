package finance.idem.infrastructure.persistence.workflow

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "workflow_plans")
class WorkflowPlanDataModel(
    @Id
    val id: UUID,

    @Column(name = "tenant_id", nullable = false)
    val tenantId: UUID,

    @Column(name = "agent_id", nullable = false)
    val agentId: String,

    @Column(name = "session_id", nullable = false)
    val sessionId: String,

    val intent: String?,

    @Column(nullable = false)
    val status: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "completed_at")
    val completedAt: Instant?,

    @Column(name = "rolled_back_at")
    val rolledBackAt: Instant?,

    @Column(name = "rollback_reason")
    val rollbackReason: String?,

    @OneToMany(mappedBy = "workflowPlan", cascade = [CascadeType.ALL], fetch = FetchType.EAGER, orphanRemoval = true)
    val steps: MutableList<WorkflowStepDataModel> = mutableListOf(),
) {
    constructor() : this(
        UUID.randomUUID(), UUID.randomUUID(), "", "", null, "PLANNED", Instant.now(), null, null, null,
    )
}
