package finance.idem.application.reconciliation

import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ReconcileBatchModelsTest {
    private val tenantId = TenantId.generate()
    private val txId = TransactionId.generate()

    @Test
    fun `ReconcileBatchCommand holds fields`() {
        val cmd = ReconcileBatchCommand(listOf(txId), tenantId)
        assertEquals(listOf(txId), cmd.transactionIds)
        assertEquals(tenantId, cmd.tenantId)
        assertEquals(cmd, cmd.copy())
    }

    @Test
    fun `ReconcileBatchItemResult holds fields`() {
        val result = ReconcileBatchItemResult(txId, ReconcileOutcome.SETTLED)
        assertEquals(txId, result.transactionId)
        assertEquals(ReconcileOutcome.SETTLED, result.outcome)
        assertEquals(result, result.copy())
    }

    @Test
    fun `ReconcileOutcome has all expected values`() {
        val names = ReconcileOutcome.entries.map { it.name }.toSet()
        assertEquals(setOf("SETTLED", "UNMATCHED", "NOT_APPLICABLE", "NOT_FOUND"), names)
    }
}
