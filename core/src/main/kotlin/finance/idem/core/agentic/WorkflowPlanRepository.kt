package finance.idem.core.agentic

import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.WorkflowPlanId
import java.time.Instant

interface WorkflowPlanRepository {
    fun insert(plan: WorkflowPlan)

    fun updateStatus(
        id: WorkflowPlanId,
        tenantId: TenantId,
        status: WorkflowStatus,
        completedAt: Instant? = null,
        rolledBackAt: Instant? = null,
        rollbackReason: String? = null,
    )

    fun updateStep(
        id: WorkflowPlanId,
        tenantId: TenantId,
        step: WorkflowStep,
    )

    fun findById(
        id: WorkflowPlanId,
        tenantId: TenantId,
    ): WorkflowPlan?

    /** The plan owning the step whose transactionId matches, if any — used to trace a
     * reorg-reversed settlement back to its originating agent workflow (see
     * ReorgReversalService). Null when the transaction was not posted as a workflow step
     * (most on-chain transactions aren't agent-originated). */
    fun findByTransactionId(
        transactionId: TransactionId,
        tenantId: TenantId,
    ): WorkflowPlan?
}
