package finance.idem.core.agentic

import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.StablecoinToken
import finance.idem.core.monetary.FiatEntry
import finance.idem.core.monetary.OnChainEntry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PolicyGuardTest {

    private val ctx = AgentContext(agentId = "agent-1", sessionId = "sess-abc")

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun fiatDebitLine(amount: String, accountId: AccountId = AccountId.generate()) =
        LedgerIntentLine(
            accountId = accountId,
            entryType = EntryType.DEBIT,
            monetaryEntry = FiatEntry(
                amount = MonetaryAmount.of(amount),
                currency = FiatCurrency.USD,
                rail = PaymentRail.ACH,
            ),
        )

    private fun fiatCreditLine(amount: String, accountId: AccountId = AccountId.generate()) =
        LedgerIntentLine(
            accountId = accountId,
            entryType = EntryType.CREDIT,
            monetaryEntry = FiatEntry(
                amount = MonetaryAmount.of(amount),
                currency = FiatCurrency.USD,
                rail = PaymentRail.ACH,
            ),
        )

    private fun onChainDebitLine(
        amount: String,
        token: StablecoinToken = StablecoinToken.USDC,
        chainId: ChainId = ChainId.EVM,
        accountId: AccountId = AccountId.generate(),
    ) = LedgerIntentLine(
        accountId = accountId,
        entryType = EntryType.DEBIT,
        monetaryEntry = OnChainEntry(
            amount = MonetaryAmount.of(amount),
            token = token,
            chainId = chainId,
            txHash = "0xabc",
            blockNumber = 1L,
            walletAddress = "0xwallet",
            tokenContract = "0xcontract",
        ),
    )

    private fun onChainCreditLine(
        amount: String,
        token: StablecoinToken = StablecoinToken.USDC,
        chainId: ChainId = ChainId.EVM,
        accountId: AccountId = AccountId.generate(),
    ) = LedgerIntentLine(
        accountId = accountId,
        entryType = EntryType.CREDIT,
        monetaryEntry = OnChainEntry(
            amount = MonetaryAmount.of(amount),
            token = token,
            chainId = chainId,
            txHash = "0xabc",
            blockNumber = 1L,
            walletAddress = "0xwallet",
            tokenContract = "0xcontract",
        ),
    )

    private fun approved(result: PolicyEvaluationResult) = assertIs<PolicyEvaluationResult.Approved>(result)
    private fun denied(result: PolicyEvaluationResult): PolicyEvaluationResult.Denied =
        assertIs<PolicyEvaluationResult.Denied>(result)

    // ── Empty rules ───────────────────────────────────────────────────────────

    @Test
    fun `empty rules always returns Approved`() {
        val intent = LedgerIntent(lines = listOf(fiatDebitLine("1000")))
        approved(PolicyGuard.evaluate(ctx, intent, emptyList()))
    }

    // ── MaxDebitPerSession ───────────────────────────────────────────────────

    @Test
    fun `MaxDebitPerSession - approved when intent debit is exactly at limit`() {
        val rule = PolicyRule.MaxDebitPerSession(MonetaryAmount.of("100"))
        val intent = LedgerIntent(lines = listOf(fiatDebitLine("100")))
        approved(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
    }

    @Test
    fun `MaxDebitPerSession - approved when intent debit is below limit`() {
        val rule = PolicyRule.MaxDebitPerSession(MonetaryAmount.of("100"))
        val intent = LedgerIntent(lines = listOf(fiatDebitLine("99.99")))
        approved(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
    }

    @Test
    fun `MaxDebitPerSession - denied when intent debit exceeds limit`() {
        val rule = PolicyRule.MaxDebitPerSession(MonetaryAmount.of("100"))
        val intent = LedgerIntent(lines = listOf(fiatDebitLine("100.01")))
        val result = denied(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
        assertEquals(1, result.violations.size)
        assertEquals(rule, result.violations.first().rule)
    }

    @Test
    fun `MaxDebitPerSession - prior total rolls up with intent debit`() {
        val rule = PolicyRule.MaxDebitPerSession(MonetaryAmount.of("100"))
        val intent = LedgerIntent(
            lines = listOf(fiatDebitLine("60")),
            priorSessionDebitTotal = MonetaryAmount.of("50"),
        )
        val result = denied(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
        assertEquals(1, result.violations.size)
    }

    @Test
    fun `MaxDebitPerSession - approved when prior total plus intent is exactly at limit`() {
        val rule = PolicyRule.MaxDebitPerSession(MonetaryAmount.of("100"))
        val intent = LedgerIntent(
            lines = listOf(fiatDebitLine("50")),
            priorSessionDebitTotal = MonetaryAmount.of("50"),
        )
        approved(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
    }

    @Test
    fun `MaxDebitPerSession - credit-only lines contribute zero to debit total`() {
        val rule = PolicyRule.MaxDebitPerSession(MonetaryAmount.of("100"))
        val intent = LedgerIntent(lines = listOf(fiatCreditLine("500")))
        approved(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
    }

    @Test
    fun `MaxDebitPerSession - explicit zero prior session total does not inflate the running sum`() {
        val rule = PolicyRule.MaxDebitPerSession(MonetaryAmount.of("100"))
        val intent = LedgerIntent(
            lines = listOf(fiatDebitLine("100")),
            priorSessionDebitTotal = MonetaryAmount.of("0"),
        )
        approved(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
    }

    // ── MaxDebitPerHour ──────────────────────────────────────────────────────

    @Test
    fun `MaxDebitPerHour - approved when intent plus prior hourly total is at limit`() {
        val rule = PolicyRule.MaxDebitPerHour(MonetaryAmount.of("200"))
        val intent = LedgerIntent(
            lines = listOf(fiatDebitLine("100")),
            priorHourlyDebitTotal = MonetaryAmount.of("100"),
        )
        approved(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
    }

    @Test
    fun `MaxDebitPerHour - denied when intent plus prior hourly total exceeds limit`() {
        val rule = PolicyRule.MaxDebitPerHour(MonetaryAmount.of("200"))
        val intent = LedgerIntent(
            lines = listOf(fiatDebitLine("101")),
            priorHourlyDebitTotal = MonetaryAmount.of("100"),
        )
        val result = denied(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
        assertEquals(1, result.violations.size)
        assertEquals(rule, result.violations.first().rule)
    }

    @Test
    fun `MaxDebitPerHour - session total does not affect hourly check`() {
        val rule = PolicyRule.MaxDebitPerHour(MonetaryAmount.of("100"))
        val intent = LedgerIntent(
            lines = listOf(fiatDebitLine("50")),
            priorSessionDebitTotal = MonetaryAmount.of("500"),
            priorHourlyDebitTotal = MonetaryAmount.of("40"),
        )
        approved(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
    }

    // ── ForbiddenAccountPair ─────────────────────────────────────────────────

    @Test
    fun `ForbiddenAccountPair - denied when both debit and credit sides are present`() {
        val debitAccountId = AccountId.generate()
        val creditAccountId = AccountId.generate()
        val rule = PolicyRule.ForbiddenAccountPair(
            debitAccount = debitAccountId,
            creditAccount = creditAccountId,
        )
        val intent = LedgerIntent(
            lines = listOf(
                fiatDebitLine("100", accountId = debitAccountId),
                fiatCreditLine("100", accountId = creditAccountId),
            ),
        )
        val result = denied(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
        assertEquals(1, result.violations.size)
        assertEquals(rule, result.violations.first().rule)
    }

    @Test
    fun `ForbiddenAccountPair - approved when only debit side is present`() {
        val debitAccountId = AccountId.generate()
        val rule = PolicyRule.ForbiddenAccountPair(
            debitAccount = debitAccountId,
            creditAccount = AccountId.generate(),
        )
        val intent = LedgerIntent(
            lines = listOf(fiatDebitLine("100", accountId = debitAccountId)),
        )
        approved(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
    }

    @Test
    fun `ForbiddenAccountPair - approved when only credit side is present`() {
        val creditAccountId = AccountId.generate()
        val rule = PolicyRule.ForbiddenAccountPair(
            debitAccount = AccountId.generate(),
            creditAccount = creditAccountId,
        )
        val intent = LedgerIntent(
            lines = listOf(fiatCreditLine("100", accountId = creditAccountId)),
        )
        approved(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
    }

    @Test
    fun `ForbiddenAccountPair - approved when lines involve different accounts`() {
        val rule = PolicyRule.ForbiddenAccountPair(
            debitAccount = AccountId.generate(),
            creditAccount = AccountId.generate(),
        )
        val intent = LedgerIntent(
            lines = listOf(
                fiatDebitLine("100"),
                fiatCreditLine("100"),
            ),
        )
        approved(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
    }

    @Test
    fun `ForbiddenAccountPair - same account as both debit and credit is denied`() {
        val sharedAccount = AccountId.generate()
        val rule = PolicyRule.ForbiddenAccountPair(
            debitAccount = sharedAccount,
            creditAccount = sharedAccount,
        )
        val intent = LedgerIntent(
            lines = listOf(
                fiatDebitLine("100", accountId = sharedAccount),
                fiatCreditLine("100", accountId = sharedAccount),
            ),
        )
        val result = denied(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
        assertEquals(1, result.violations.size)
    }

    // ── RequireHumanApprovalAbove ────────────────────────────────────────────

    @Test
    fun `RequireHumanApprovalAbove - denied when debit strictly exceeds threshold`() {
        val rule = PolicyRule.RequireHumanApprovalAbove(MonetaryAmount.of("1000"))
        val intent = LedgerIntent(lines = listOf(fiatDebitLine("1000.01")))
        val result = denied(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
        assertEquals(1, result.violations.size)
        assertEquals(rule, result.violations.first().rule)
    }

    @Test
    fun `RequireHumanApprovalAbove - approved when debit is exactly at threshold`() {
        val rule = PolicyRule.RequireHumanApprovalAbove(MonetaryAmount.of("1000"))
        val intent = LedgerIntent(lines = listOf(fiatDebitLine("1000")))
        approved(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
    }

    @Test
    fun `RequireHumanApprovalAbove - approved when debit is below threshold`() {
        val rule = PolicyRule.RequireHumanApprovalAbove(MonetaryAmount.of("1000"))
        val intent = LedgerIntent(lines = listOf(fiatDebitLine("999.99")))
        approved(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
    }

    @Test
    fun `RequireHumanApprovalAbove - credit lines above threshold do not trigger violation`() {
        val rule = PolicyRule.RequireHumanApprovalAbove(MonetaryAmount.of("1000"))
        val intent = LedgerIntent(lines = listOf(fiatCreditLine("9999")))
        approved(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
    }

    @Test
    fun `RequireHumanApprovalAbove - each offending debit line produces its own violation`() {
        val rule = PolicyRule.RequireHumanApprovalAbove(MonetaryAmount.of("1000"))
        val intent = LedgerIntent(
            lines = listOf(
                fiatDebitLine("1500"),
                fiatDebitLine("2000"),
            ),
        )
        val result = denied(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
        assertEquals(2, result.violations.size)
        result.violations.forEach { assertEquals(rule, it.rule) }
    }

    // ── AllowedTokens ────────────────────────────────────────────────────────

    @Test
    fun `AllowedTokens - approved when all on-chain lines use allowed token`() {
        val rule = PolicyRule.AllowedTokens(setOf(StablecoinToken.USDC))
        val intent = LedgerIntent(
            lines = listOf(
                onChainDebitLine("100", token = StablecoinToken.USDC),
                onChainCreditLine("100", token = StablecoinToken.USDC),
            ),
        )
        approved(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
    }

    @Test
    fun `AllowedTokens - denied when on-chain line uses disallowed token`() {
        val rule = PolicyRule.AllowedTokens(setOf(StablecoinToken.USDC))
        val intent = LedgerIntent(
            lines = listOf(onChainDebitLine("100", token = StablecoinToken.USDT)),
        )
        val result = denied(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
        assertEquals(1, result.violations.size)
        assertEquals(rule, result.violations.first().rule)
    }

    @Test
    fun `AllowedTokens - fiat lines are ignored even when token set is restrictive`() {
        val rule = PolicyRule.AllowedTokens(setOf(StablecoinToken.USDC))
        val intent = LedgerIntent(lines = listOf(fiatDebitLine("500")))
        approved(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
    }

    @Test
    fun `AllowedTokens - multiple disallowed tokens are all reported in one violation`() {
        val rule = PolicyRule.AllowedTokens(setOf(StablecoinToken.USDC))
        val intent = LedgerIntent(
            lines = listOf(
                onChainDebitLine("100", token = StablecoinToken.USDT),
                onChainCreditLine("100", token = StablecoinToken.BRZ),
            ),
        )
        val result = denied(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
        assertEquals(1, result.violations.size)
    }

    // ── AllowedChains ────────────────────────────────────────────────────────

    @Test
    fun `AllowedChains - approved when all on-chain lines use allowed chain`() {
        val rule = PolicyRule.AllowedChains(setOf(ChainId.EVM))
        val intent = LedgerIntent(
            lines = listOf(
                onChainDebitLine("100", chainId = ChainId.EVM),
                onChainCreditLine("100", chainId = ChainId.EVM),
            ),
        )
        approved(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
    }

    @Test
    fun `AllowedChains - denied when on-chain line uses disallowed chain`() {
        val rule = PolicyRule.AllowedChains(setOf(ChainId.EVM))
        val intent = LedgerIntent(
            lines = listOf(onChainDebitLine("100", chainId = ChainId.SOLANA)),
        )
        val result = denied(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
        assertEquals(1, result.violations.size)
        assertEquals(rule, result.violations.first().rule)
    }

    @Test
    fun `AllowedChains - fiat lines are ignored`() {
        val rule = PolicyRule.AllowedChains(setOf(ChainId.EVM))
        val intent = LedgerIntent(lines = listOf(fiatDebitLine("500")))
        approved(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
    }

    @Test
    fun `AllowedChains - multiple disallowed chains are all reported in one violation`() {
        val rule = PolicyRule.AllowedChains(setOf(ChainId.EVM))
        val intent = LedgerIntent(
            lines = listOf(
                onChainDebitLine("100", chainId = ChainId.SOLANA),
                onChainCreditLine("100", chainId = ChainId.TRON),
            ),
        )
        val result = denied(PolicyGuard.evaluate(ctx, intent, listOf(rule)))
        assertEquals(1, result.violations.size)
    }

    // ── Multi-rule scenarios ─────────────────────────────────────────────────

    @Test
    fun `all rules pass - returns Approved`() {
        val rules = listOf(
            PolicyRule.MaxDebitPerSession(MonetaryAmount.of("1000")),
            PolicyRule.AllowedTokens(setOf(StablecoinToken.USDC)),
        )
        val intent = LedgerIntent(
            lines = listOf(
                onChainDebitLine("100", token = StablecoinToken.USDC),
                onChainCreditLine("100", token = StablecoinToken.USDC),
            ),
        )
        approved(PolicyGuard.evaluate(ctx, intent, rules))
    }

    @Test
    fun `one rule fails - returns Denied with one violation`() {
        val failingRule = PolicyRule.MaxDebitPerSession(MonetaryAmount.of("50"))
        val passingRule = PolicyRule.AllowedTokens(setOf(StablecoinToken.USDC))
        val intent = LedgerIntent(
            lines = listOf(onChainDebitLine("100", token = StablecoinToken.USDC)),
        )
        val result = denied(PolicyGuard.evaluate(ctx, intent, listOf(failingRule, passingRule)))
        assertEquals(1, result.violations.size)
        assertEquals(failingRule, result.violations.first().rule)
    }

    @Test
    fun `two rules fail - all violations collected, not fail-fast`() {
        val rule1 = PolicyRule.MaxDebitPerSession(MonetaryAmount.of("50"))
        val rule2 = PolicyRule.AllowedTokens(setOf(StablecoinToken.USDC))
        val intent = LedgerIntent(
            lines = listOf(onChainDebitLine("100", token = StablecoinToken.USDT)),
        )
        val result = denied(PolicyGuard.evaluate(ctx, intent, listOf(rule1, rule2)))
        assertEquals(2, result.violations.size)
        assertEquals(rule1, result.violations[0].rule)
        assertEquals(rule2, result.violations[1].rule)
    }

    @Test
    fun `MaxDebitPerSession and MaxDebitPerHour can both be violated simultaneously`() {
        val sessionRule = PolicyRule.MaxDebitPerSession(MonetaryAmount.of("100"))
        val hourlyRule = PolicyRule.MaxDebitPerHour(MonetaryAmount.of("80"))
        val intent = LedgerIntent(
            lines = listOf(fiatDebitLine("90")),
            priorSessionDebitTotal = MonetaryAmount.of("20"),
            priorHourlyDebitTotal = MonetaryAmount.of("10"),
        )
        val result = denied(PolicyGuard.evaluate(ctx, intent, listOf(sessionRule, hourlyRule)))
        assertEquals(2, result.violations.size)
    }
}
