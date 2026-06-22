package finance.idem.infrastructure.persistence.workflow

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "workflow_plan_steps")
class WorkflowPlanStepDataModel(
    @Id
    val id: UUID,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_plan_id", nullable = false)
    val workflowPlan: WorkflowPlanDataModel,

    @Column(name = "tenant_id", nullable = false)
    val tenantId: UUID,

    @Column(name = "step_index", nullable = false)
    val stepIndex: Int,

    @Column(name = "idempotency_key", nullable = false)
    val idempotencyKey: String,

    @Column(nullable = false)
    val status: String,

    @Column(name = "transaction_id")
    val transactionId: UUID?,
) {
    protected constructor() : this(
        UUID.randomUUID(), WorkflowPlanDataModel(), UUID.randomUUID(), 0, "", "PENDING", null,
    )
}
