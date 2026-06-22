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

    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,

    @Column(name = "committed_at")
    val committedAt: Instant?,

    @OneToMany(mappedBy = "workflowPlan", cascade = [CascadeType.ALL], fetch = FetchType.EAGER, orphanRemoval = true)
    val steps: MutableList<WorkflowPlanStepDataModel> = mutableListOf(),
) {
    constructor() : this(
        UUID.randomUUID(), UUID.randomUUID(), "", "", null, "PLANNED", Instant.now(), null,
    )
}
