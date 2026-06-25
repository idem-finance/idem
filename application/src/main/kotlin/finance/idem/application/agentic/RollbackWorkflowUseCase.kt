package finance.idem.application.agentic

interface RollbackWorkflowUseCase {
    fun execute(cmd: RollbackWorkflowCommand): Result<RollbackWorkflowSummary>
}
