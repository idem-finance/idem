package finance.idem.application.agentic

import finance.idem.core.WorkflowPlanId

interface ExecuteWorkflowUseCase {
    fun execute(cmd: ExecuteWorkflowCommand): Result<WorkflowPlanId>
}
