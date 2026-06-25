package finance.idem.application.reconciliation

import finance.idem.core.AccountId
import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReconcileEntriesCommandTest {

    private val tenantId = TenantId.generate()
    private val from = Instant.parse("2025-01-01T00:00:00Z")
    private val to = Instant.parse("2025-01-31T23:59:59Z")

    @Test
    fun `ReconcileEntriesCommand holds all fields with optional accountId`() {
        val accountId = AccountId.generate()
        val cmd = ReconcileEntriesCommand(tenantId = tenantId, accountId = accountId, from = from, to = to)

        assertEquals(tenantId, cmd.tenantId)
        assertEquals(accountId, cmd.accountId)
        assertEquals(from, cmd.from)
        assertEquals(to, cmd.to)
        assertEquals(cmd, cmd.copy())
    }

    @Test
    fun `ReconcileEntriesCommand accountId defaults to null`() {
        val cmd = ReconcileEntriesCommand(tenantId = tenantId, from = from, to = to)
        assertNull(cmd.accountId)
    }

    @Test
    fun `tolerancePercent stored as BigDecimal and defaults to null`() {
        val cmdNull = ReconcileEntriesCommand(tenantId = tenantId, from = from, to = to)
        assertNull(cmdNull.tolerancePercent)

        val cmdWithTolerance = ReconcileEntriesCommand(
            tenantId = tenantId, from = from, to = to,
            tolerancePercent = BigDecimal("0.5"),
        )
        assertEquals(BigDecimal("0.5"), cmdWithTolerance.tolerancePercent)
    }
}
