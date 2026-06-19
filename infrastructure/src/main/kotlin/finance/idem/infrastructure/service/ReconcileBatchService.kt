package finance.idem.infrastructure.service

import finance.idem.application.reconciliation.BasicReconciliationUseCase
import finance.idem.application.reconciliation.ReconcileBatchCommand
import finance.idem.application.reconciliation.ReconcileBatchItemResult
import finance.idem.application.reconciliation.ReconcileBatchUseCase
import finance.idem.application.reconciliation.ReconcileOutcome
import finance.idem.application.reconciliation.ReconciliationResult
import finance.idem.core.ledger.TransactionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ReconcileBatchService(
    private val transactionRepository: TransactionRepository,
    private val reconciliationUseCase: BasicReconciliationUseCase,
) : ReconcileBatchUseCase {

    @Transactional
    override fun execute(cmd: ReconcileBatchCommand): List<ReconcileBatchItemResult> =
        cmd.transactionIds.map { txId ->
            val tx = transactionRepository.findById(txId, cmd.tenantId)
                ?: return@map ReconcileBatchItemResult(txId, ReconcileOutcome.NOT_FOUND)

            val outcome = when (reconciliationUseCase.reconcile(tx)) {
                is ReconciliationResult.Settled -> ReconcileOutcome.SETTLED
                is ReconciliationResult.Unmatched -> ReconcileOutcome.UNMATCHED
                ReconciliationResult.NotApplicable -> ReconcileOutcome.NOT_APPLICABLE
            }
            ReconcileBatchItemResult(txId, outcome)
        }
}
