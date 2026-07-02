package finance.idem.infrastructure.service

import finance.idem.application.reconciliation.BasicReconciliationUseCase
import finance.idem.application.reconciliation.ReconcileBatchCommand
import finance.idem.application.reconciliation.ReconcileOutcome
import finance.idem.application.reconciliation.ReconciliationResult
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.ledger.Settlement
import finance.idem.core.ledger.TransactionRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals

@ExtendWith(MockitoExtension::class)
class ReconcileBatchServiceTest {
    @Mock
    private lateinit var transactionRepository: TransactionRepository

    @Mock
    private lateinit var reconciliationUseCase: BasicReconciliationUseCase

    @Mock
    private lateinit var settlement: Settlement

    private val service by lazy { ReconcileBatchService(transactionRepository, reconciliationUseCase) }

    private val tenantId = TenantId.generate()
    private val txId1 = TransactionId.generate()
    private val txId2 = TransactionId.generate()

    @Mock
    private lateinit var mockTx: finance.idem.core.ledger.Transaction

    @Test
    fun `returns NOT_FOUND when transaction not found`() {
        whenever(transactionRepository.findById(any(), any())).thenReturn(null)

        val results = service.execute(ReconcileBatchCommand(listOf(txId1), tenantId))

        assertEquals(1, results.size)
        assertEquals(txId1, results[0].transactionId)
        assertEquals(ReconcileOutcome.NOT_FOUND, results[0].outcome)
    }

    @Test
    fun `returns SETTLED when reconciliation settles`() {
        whenever(transactionRepository.findById(any(), any())).thenReturn(mockTx)
        whenever(reconciliationUseCase.reconcile(mockTx)).thenReturn(ReconciliationResult.Settled(settlement))

        val results = service.execute(ReconcileBatchCommand(listOf(txId1), tenantId))

        assertEquals(ReconcileOutcome.SETTLED, results[0].outcome)
    }

    @Test
    fun `returns UNMATCHED when reconciliation is unmatched`() {
        whenever(transactionRepository.findById(any(), any())).thenReturn(mockTx)
        whenever(reconciliationUseCase.reconcile(mockTx)).thenReturn(ReconciliationResult.Unmatched(settlement))

        val results = service.execute(ReconcileBatchCommand(listOf(txId1), tenantId))

        assertEquals(ReconcileOutcome.UNMATCHED, results[0].outcome)
    }

    @Test
    fun `returns NOT_APPLICABLE when reconciliation is not applicable`() {
        whenever(transactionRepository.findById(any(), any())).thenReturn(mockTx)
        whenever(reconciliationUseCase.reconcile(mockTx)).thenReturn(ReconciliationResult.NotApplicable)

        val results = service.execute(ReconcileBatchCommand(listOf(txId1), tenantId))

        assertEquals(ReconcileOutcome.NOT_APPLICABLE, results[0].outcome)
    }

    @Test
    fun `processes batch of multiple transactions`() {
        whenever(transactionRepository.findById(txId1, tenantId)).thenReturn(mockTx)
        whenever(transactionRepository.findById(txId2, tenantId)).thenReturn(null)
        whenever(reconciliationUseCase.reconcile(mockTx)).thenReturn(ReconciliationResult.NotApplicable)

        val results = service.execute(ReconcileBatchCommand(listOf(txId1, txId2), tenantId))

        assertEquals(2, results.size)
        assertEquals(ReconcileOutcome.NOT_APPLICABLE, results[0].outcome)
        assertEquals(ReconcileOutcome.NOT_FOUND, results[1].outcome)
    }
}
