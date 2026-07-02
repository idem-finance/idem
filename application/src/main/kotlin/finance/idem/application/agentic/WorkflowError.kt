package finance.idem.application.agentic

import finance.idem.core.WorkflowPlanId

sealed class WorkflowError(
    message: String,
) : Exception(message)

class WorkflowPlanNotFound(
    val workflowPlanId: WorkflowPlanId,
) : WorkflowError("Workflow plan not found: ${workflowPlanId.value}")
