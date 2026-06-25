package finance.idem.application.agentic

import finance.idem.core.TransactionId
import finance.idem.core.WorkflowPlanId

data class RollbackWorkflowSummary(
    val workflowPlanId: WorkflowPlanId,
    val compensatedSteps: List<CompensatedStepSummary>,
    val status: String,
)

data class CompensatedStepSummary(
    val stepOrder: Int,
    val description: String,
    val compensatingTransactionId: TransactionId?,
)
