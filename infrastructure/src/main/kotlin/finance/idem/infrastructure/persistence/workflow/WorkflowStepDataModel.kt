package finance.idem.infrastructure.persistence.workflow

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "workflow_steps")
class WorkflowStepDataModel(
    @Id
    val id: UUID,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_plan_id", nullable = false)
    val workflowPlan: WorkflowPlanDataModel,
    @Column(name = "tenant_id", nullable = false)
    val tenantId: UUID,
    @Column(name = "step_order", nullable = false)
    val stepOrder: Int,
    @Column(name = "description", nullable = false)
    val description: String,
    @Column(nullable = false)
    val status: String,
    @Column(name = "transaction_id")
    val transactionId: UUID?,
    @Column(name = "executed_at")
    val executedAt: Instant?,
    @Column(name = "compensating_transaction_id")
    val compensatingTransactionId: UUID?,
) {
    protected constructor() : this(
        UUID.randomUUID(),
        WorkflowPlanDataModel(),
        UUID.randomUUID(),
        0,
        "",
        "PENDING",
        null,
        null,
        null,
    )
}
