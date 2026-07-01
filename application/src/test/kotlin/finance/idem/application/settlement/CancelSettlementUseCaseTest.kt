package finance.idem.application.settlement

import finance.idem.core.TenantId
import finance.idem.core.ledger.EntryStatus
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CancelSettlementUseCaseTest {
    private val tenantId = TenantId.generate()

    @Test
    fun `command carries id and tenantId`() {
        val id = UUID.randomUUID()
        val cmd = CancelSettlementCommand(id, tenantId)
        assertEquals(id, cmd.id)
        assertEquals(tenantId, cmd.tenantId)
    }

    @Test
    fun `SettlementNotFound message contains the id`() {
        val id = UUID.randomUUID()
        val err = SettlementNotFound(id)
        assertTrue(err.message!!.contains(id.toString()))
        assertEquals(id, err.id)
    }

    @Test
    fun `SettlementAlreadyTerminal message contains the status`() {
        EntryStatus.entries.filter { it != EntryStatus.PENDING }.forEach { status ->
            val err = SettlementAlreadyTerminal(status)
            assertTrue(err.message!!.contains(status.name))
            assertEquals(status, err.status)
        }
    }
}
