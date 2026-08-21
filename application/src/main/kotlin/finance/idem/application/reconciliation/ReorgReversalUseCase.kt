package finance.idem.application.reconciliation

/**
 * Reverses a previously matched/settled entry when a chain reorg invalidates it — posts a
 * compensating transaction (mirrors [finance.idem.application.agentic.RollbackWorkflowUseCase]'s
 * saga pattern) rather than mutating the original settlement's evidence. Must be its own
 * transactional unit — never nested inside a webhook's per-activity processing loop.
 */
interface ReorgReversalUseCase {
    fun execute(cmd: ReorgReversalCommand): Result<ReorgReversalResult>
}
