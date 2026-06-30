package finance.idem.infrastructure.service

import finance.idem.core.TenantId
import finance.idem.core.agentic.PolicyRepository
import finance.idem.core.agentic.PolicyRule
import finance.idem.core.agentic.PolicyRuleId
import finance.idem.core.agentic.PolicyRuleRecord
import finance.idem.core.MonetaryAmount
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManagePolicyRulesServiceTest {

    private val tenantId = TenantId.generate()
    private val rule = PolicyRule.MaxDebitPerSession(MonetaryAmount.of("100"))
    private val record = PolicyRuleRecord(PolicyRuleId.generate(), null, rule, Instant.now())

    @Test
    fun `create delegates to policyRepository save and returns the record`() {
        var savedTenantId: TenantId? = null
        var savedPrefix: String? = null
        var savedRule: PolicyRule? = null
        val repo = fakePolicyRepository(saveResult = record, onSave = { t, p, r -> savedTenantId = t; savedPrefix = p; savedRule = r })

        val result = ManagePolicyRulesService(repo).create(tenantId, "sk_agent_x", rule)

        assertEquals(record, result)
        assertEquals(tenantId, savedTenantId)
        assertEquals("sk_agent_x", savedPrefix)
        assertEquals(rule, savedRule)
    }

    @Test
    fun `findAll delegates to policyRepository findAll`() {
        var queriedTenantId: TenantId? = null
        val repo = fakePolicyRepository(findAllResult = listOf(record), onFindAll = { queriedTenantId = it })

        val result = ManagePolicyRulesService(repo).findAll(tenantId)

        assertEquals(listOf(record), result)
        assertEquals(tenantId, queriedTenantId)
    }

    @Test
    fun `delete returns true when repository returns true`() {
        val repo = fakePolicyRepository(deleteResult = true)
        assertTrue(ManagePolicyRulesService(repo).delete(tenantId, record.id))
    }

    @Test
    fun `delete returns false when repository returns false`() {
        val repo = fakePolicyRepository(deleteResult = false)
        assertFalse(ManagePolicyRulesService(repo).delete(tenantId, PolicyRuleId.generate()))
    }

    // ── Fake ────────────────────────────────────────────────────────────────────

    private fun fakePolicyRepository(
        saveResult: PolicyRuleRecord = record,
        findAllResult: List<PolicyRuleRecord> = emptyList(),
        deleteResult: Boolean = false,
        onSave: (TenantId, String?, PolicyRule) -> Unit = { _, _, _ -> },
        onFindAll: (TenantId) -> Unit = {},
    ) = object : PolicyRepository {
        override fun save(tenantId: TenantId, agentKeyPrefix: String?, rule: PolicyRule): PolicyRuleRecord {
            onSave(tenantId, agentKeyPrefix, rule)
            return saveResult
        }
        override fun findAll(tenantId: TenantId): List<PolicyRuleRecord> {
            onFindAll(tenantId)
            return findAllResult
        }
        override fun delete(tenantId: TenantId, ruleId: PolicyRuleId): Boolean = deleteResult
        override fun findEffective(tenantId: TenantId, agentKeyPrefix: String?): List<PolicyRule> = emptyList()
    }
}
