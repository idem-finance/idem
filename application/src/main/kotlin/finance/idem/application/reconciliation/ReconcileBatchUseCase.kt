package finance.idem.application.reconciliation

interface ReconcileBatchUseCase {
    fun execute(cmd: ReconcileBatchCommand): List<ReconcileBatchItemResult>
}
