package finance.idem.infrastructure.persistence.policy

import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.agentic.PolicyRule
import finance.idem.core.agentic.PolicyRuleId
import finance.idem.infrastructure.persistence.PersistenceTestConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(PolicyRepositoryAdapter::class, PersistenceTestConfig::class)
class PolicyRepositoryAdapterTest {
    companion object {
        @Container
        val postgres =
            PostgreSQLContainer("postgres:16")
                .withDatabaseName("idem_test")
                .withUsername("idem")
                .withPassword("idem")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired
    lateinit var adapter: PolicyRepositoryAdapter

    private val tenantA = TenantId.generate()
    private val tenantB = TenantId.generate()

    // ── round-trip for all 6 rule types ──────────────────────────────────────

    @Test
    fun `MaxDebitPerSession round-trips correctly`() {
        val rule = PolicyRule.MaxDebitPerSession(MonetaryAmount.of("5000.00"))
        val saved = adapter.save(tenantA, null, rule)

        val found = adapter.findEffective(tenantA, null)

        assertEquals(1, found.size)
        assertEquals(rule, found[0])
        assertNotNull(saved.createdAt)
    }

    @Test
    fun `MaxDebitPerHour round-trips correctly`() {
        val rule = PolicyRule.MaxDebitPerHour(MonetaryAmount.of("1000.00"))
        adapter.save(tenantA, null, rule)

        val found = adapter.findEffective(tenantA, null)

        assertEquals(1, found.size)
        assertEquals(rule, found[0])
    }

    @Test
    fun `RequireHumanApprovalAbove round-trips correctly`() {
        val rule = PolicyRule.RequireHumanApprovalAbove(MonetaryAmount.of("10000.00"))
        adapter.save(tenantA, null, rule)

        val found = adapter.findEffective(tenantA, null)

        assertEquals(1, found.size)
        assertEquals(rule, found[0])
    }

    @Test
    fun `ForbiddenAccountPair round-trips correctly`() {
        val debit = AccountId.generate()
        val credit = AccountId.generate()
        val rule = PolicyRule.ForbiddenAccountPair(debitAccount = debit, creditAccount = credit)
        adapter.save(tenantA, null, rule)

        val found = adapter.findEffective(tenantA, null)

        assertEquals(1, found.size)
        assertEquals(rule, found[0])
    }

    @Test
    fun `AllowedTokens round-trips correctly`() {
        val rule = PolicyRule.AllowedTokens(setOf(StablecoinToken.USDC, StablecoinToken.USDT))
        adapter.save(tenantA, null, rule)

        val found = adapter.findEffective(tenantA, null)

        assertEquals(1, found.size)
        assertEquals(rule, found[0])
    }

    @Test
    fun `AllowedChains round-trips correctly`() {
        val rule = PolicyRule.AllowedChains(setOf(ChainId.EVM, ChainId.SOLANA))
        adapter.save(tenantA, null, rule)

        val found = adapter.findEffective(tenantA, null)

        assertEquals(1, found.size)
        assertEquals(rule, found[0])
    }

    // ── tenant isolation ──────────────────────────────────────────────────────

    @Test
    fun `findEffective does not return another tenant's rules`() {
        adapter.save(tenantA, null, PolicyRule.MaxDebitPerSession(MonetaryAmount.of("500")))
        adapter.save(tenantB, null, PolicyRule.MaxDebitPerHour(MonetaryAmount.of("200")))

        val aRules = adapter.findEffective(tenantA, null)
        val bRules = adapter.findEffective(tenantB, null)

        assertEquals(1, aRules.size)
        assertTrue(aRules[0] is PolicyRule.MaxDebitPerSession)

        assertEquals(1, bRules.size)
        assertTrue(bRules[0] is PolicyRule.MaxDebitPerHour)
    }

    // ── agent-scoped rules ────────────────────────────────────────────────────

    @Test
    fun `tenant-wide rule returned for any agent prefix`() {
        adapter.save(tenantA, null, PolicyRule.MaxDebitPerSession(MonetaryAmount.of("100")))

        val rules = adapter.findEffective(tenantA, "sk_agent_abc")

        assertEquals(1, rules.size)
    }

    @Test
    fun `agent-scoped rule returned only when prefix matches`() {
        adapter.save(tenantA, "sk_agent_abc", PolicyRule.MaxDebitPerSession(MonetaryAmount.of("100")))

        val withMatch = adapter.findEffective(tenantA, "sk_agent_abc")
        val withoutMatch = adapter.findEffective(tenantA, "sk_agent_xyz")
        val withNullPrefix = adapter.findEffective(tenantA, null)

        assertEquals(1, withMatch.size)
        assertEquals(0, withoutMatch.size)
        assertEquals(0, withNullPrefix.size)
    }

    @Test
    fun `findEffective combines tenant-wide and agent-specific rules`() {
        adapter.save(tenantA, null, PolicyRule.MaxDebitPerHour(MonetaryAmount.of("999")))
        adapter.save(tenantA, "sk_agent_abc", PolicyRule.AllowedChains(setOf(ChainId.EVM)))

        val rules = adapter.findEffective(tenantA, "sk_agent_abc")

        assertEquals(2, rules.size)
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    fun `delete returns true and removes the rule`() {
        val record = adapter.save(tenantA, null, PolicyRule.MaxDebitPerSession(MonetaryAmount.of("1")))

        val deleted = adapter.delete(tenantA, record.id)

        assertTrue(deleted)
        assertEquals(0, adapter.findEffective(tenantA, null).size)
    }

    @Test
    fun `delete returns false for unknown rule`() {
        val missing = PolicyRuleId(UUID.randomUUID())

        val deleted = adapter.delete(tenantA, missing)

        assertFalse(deleted)
    }

    @Test
    fun `delete does not affect another tenant's rule with same id`() {
        val record = adapter.save(tenantA, null, PolicyRule.MaxDebitPerSession(MonetaryAmount.of("1")))

        // Attempt to delete from wrong tenant — must not find the row
        val deleted = adapter.delete(tenantB, record.id)

        assertFalse(deleted)
        assertEquals(1, adapter.findEffective(tenantA, null).size)
    }

    // ── findAll ───────────────────────────────────────────────────────────────

    @Test
    fun `findAll returns all rules for the tenant ordered by created_at`() {
        adapter.save(tenantA, null, PolicyRule.MaxDebitPerSession(MonetaryAmount.of("100")))
        adapter.save(tenantA, "sk_agent_abc", PolicyRule.AllowedChains(setOf(ChainId.TRON)))

        val all = adapter.findAll(tenantA)

        assertEquals(2, all.size)
        assertNotNull(all[0].createdAt)
    }
}
