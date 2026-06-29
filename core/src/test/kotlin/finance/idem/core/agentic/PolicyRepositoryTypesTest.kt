package finance.idem.core.agentic

import finance.idem.core.MonetaryAmount
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PolicyRepositoryTypesTest {

    @Test
    fun `PolicyRuleId generate produces non-null distinct values`() {
        val a = PolicyRuleId.generate()
        val b = PolicyRuleId.generate()
        assertNotEquals(a, b)
    }

    @Test
    fun `PolicyRuleId wraps the given UUID`() {
        val uuid = UUID.randomUUID()
        val id = PolicyRuleId(uuid)
        assertEquals(uuid, id.value)
    }

    @Test
    fun `PolicyRuleRecord holds all fields`() {
        val id = PolicyRuleId.generate()
        val rule = PolicyRule.MaxDebitPerSession(MonetaryAmount.of("100"))
        val now = Instant.now()
        val record = PolicyRuleRecord(id = id, agentKeyPrefix = "sk_agent_abc1", rule = rule, createdAt = now)

        assertEquals(id, record.id)
        assertEquals("sk_agent_abc1", record.agentKeyPrefix)
        assertEquals(rule, record.rule)
        assertEquals(now, record.createdAt)
    }

    @Test
    fun `PolicyRuleRecord with null agentKeyPrefix is a tenant-wide rule`() {
        val record = PolicyRuleRecord(
            id = PolicyRuleId.generate(),
            agentKeyPrefix = null,
            rule = PolicyRule.MaxDebitPerHour(MonetaryAmount.of("500")),
            createdAt = Instant.now(),
        )
        assertEquals(null, record.agentKeyPrefix)
    }
}
