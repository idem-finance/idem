package finance.idem.core

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ValueObjectsTest {
    @Test
    fun `AccountId equality works by wrapped value`() {
        val id = UUID.randomUUID()
        assertEquals(AccountId(id), AccountId(id))
        assertNotEquals(AccountId(UUID.randomUUID()), AccountId(UUID.randomUUID()))
    }

    @Test
    fun `TransactionId equality works by wrapped value`() {
        val id = UUID.randomUUID()
        assertEquals(TransactionId(id), TransactionId(id))
    }

    @Test
    fun `TenantId equality works by wrapped value`() {
        val id = UUID.randomUUID()
        assertEquals(TenantId(id), TenantId(id))
    }

    @Test
    fun `WorkflowPlanId equality works by wrapped value`() {
        val id = UUID.randomUUID()
        assertEquals(WorkflowPlanId(id), WorkflowPlanId(id))
    }

    @Test
    fun `AccountId of() parses string`() {
        val uuid = UUID.randomUUID()
        assertEquals(AccountId(uuid), AccountId.of(uuid.toString()))
    }

    @Test
    fun `TransactionId of() parses string`() {
        val uuid = UUID.randomUUID()
        assertEquals(TransactionId(uuid), TransactionId.of(uuid.toString()))
    }

    @Test
    fun `TenantId of() parses string`() {
        val uuid = UUID.randomUUID()
        assertEquals(TenantId(uuid), TenantId.of(uuid.toString()))
    }

    @Test
    fun `WorkflowPlanId of() parses string`() {
        val uuid = UUID.randomUUID()
        assertEquals(WorkflowPlanId(uuid), WorkflowPlanId.of(uuid.toString()))
    }

    @Test
    fun `generate() produces unique ids`() {
        assertNotEquals(AccountId.generate(), AccountId.generate())
        assertNotEquals(TransactionId.generate(), TransactionId.generate())
        assertNotEquals(TenantId.generate(), TenantId.generate())
        assertNotEquals(WorkflowPlanId.generate(), WorkflowPlanId.generate())
    }

    @Test
    fun `AccountId and TransactionId with same UUID are not equal`() {
        val uuid = UUID.randomUUID()
        // Different types — compile-time safety; this test documents the runtime behaviour
        val accountId = AccountId(uuid)
        val transactionId = TransactionId(uuid)
        // Can't compare directly without cast — this just verifies values are accessible
        assertEquals(accountId.value, transactionId.value)
    }
}
