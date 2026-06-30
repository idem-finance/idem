package finance.idem.core.agentic

import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
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

    @Test
    fun `typeName returns correct discriminator for every rule subtype`() {
        val debit = AccountId.generate()
        val credit = AccountId.generate()
        assertEquals("MAX_DEBIT_PER_SESSION", PolicyRule.MaxDebitPerSession(MonetaryAmount.of("100")).typeName())
        assertEquals("MAX_DEBIT_PER_HOUR", PolicyRule.MaxDebitPerHour(MonetaryAmount.of("100")).typeName())
        assertEquals("REQUIRE_HUMAN_APPROVAL_ABOVE", PolicyRule.RequireHumanApprovalAbove(MonetaryAmount.of("100")).typeName())
        assertEquals("FORBIDDEN_ACCOUNT_PAIR", PolicyRule.ForbiddenAccountPair(debit, credit).typeName())
        assertEquals("ALLOWED_TOKENS", PolicyRule.AllowedTokens(setOf(StablecoinToken.USDC)).typeName())
        assertEquals("ALLOWED_CHAINS", PolicyRule.AllowedChains(setOf(ChainId.EVM)).typeName())
    }

    @Test
    fun `params serializes amount rules to amount key`() {
        val rule = PolicyRule.MaxDebitPerSession(MonetaryAmount.of("250"))
        assertEquals(mapOf("amount" to "250"), rule.params())
    }

    @Test
    fun `params serializes ForbiddenAccountPair to both account IDs`() {
        val debit = AccountId.generate()
        val credit = AccountId.generate()
        val rule = PolicyRule.ForbiddenAccountPair(debit, credit)
        val params = rule.params()
        assertEquals(debit.value.toString(), params["debitAccountId"])
        assertEquals(credit.value.toString(), params["creditAccountId"])
    }

    @Test
    fun `params serializes AllowedTokens to list of token names`() {
        val rule = PolicyRule.AllowedTokens(setOf(StablecoinToken.USDC, StablecoinToken.USDT))
        @Suppress("UNCHECKED_CAST")
        val tokens = rule.params()["tokens"] as List<String>
        assertEquals(setOf("USDC", "USDT"), tokens.toSet())
    }

    @Test
    fun `params serializes AllowedChains to list of chain names`() {
        val rule = PolicyRule.AllowedChains(setOf(ChainId.EVM, ChainId.SOLANA))
        @Suppress("UNCHECKED_CAST")
        val chains = rule.params()["chains"] as List<String>
        assertEquals(setOf("EVM", "SOLANA"), chains.toSet())
    }
}
