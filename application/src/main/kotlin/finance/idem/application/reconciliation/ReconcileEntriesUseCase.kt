package finance.idem.application.reconciliation

interface ReconcileEntriesUseCase {
    fun execute(cmd: ReconcileEntriesCommand): Result<ReconcileEntriesResult>
}
