package finance.idem.core.agentic

import finance.idem.core.EntryType
import finance.idem.core.MonetaryAmount
import finance.idem.core.monetary.OnChainEntry

/**
 * Pure, stateless evaluator for a list of [PolicyRule]s against a [LedgerIntent].
 *
 * Contract:
 * - Never throws; always returns a [PolicyEvaluationResult].
 * - All violations are collected before returning — a single call reports every
 *   failing rule, not just the first one.
 * - Has zero infrastructure dependencies (no Spring, no JPA, no I/O).
 *
 * Callers are responsible for:
 * - Pre-computing [LedgerIntent.priorSessionDebitTotal] and [LedgerIntent.priorHourlyDebitTotal]
 *   from historical journal data before calling [evaluate].
 * - Normalizing all [MonetaryAmount] values to the same unit before constructing the intent.
 * - Throwing [PolicyViolationException] if the result is [PolicyEvaluationResult.Denied].
 */
object PolicyGuard {

    fun evaluate(
        context: AgentContext,
        intent: LedgerIntent,
        rules: List<PolicyRule>,
    ): PolicyEvaluationResult {
        val violations = mutableListOf<PolicyViolation>()

        for (rule in rules) {
            when (rule) {
                is PolicyRule.MaxDebitPerSession ->
                    checkMaxDebit(
                        rule = rule,
                        label = "session",
                        limit = rule.limit,
                        priorTotal = intent.priorSessionDebitTotal,
                        intent = intent,
                        violations = violations,
                    )

                is PolicyRule.MaxDebitPerHour ->
                    checkMaxDebit(
                        rule = rule,
                        label = "hour",
                        limit = rule.limit,
                        priorTotal = intent.priorHourlyDebitTotal,
                        intent = intent,
                        violations = violations,
                    )

                is PolicyRule.ForbiddenAccountPair ->
                    checkForbiddenPair(rule, intent, violations)

                is PolicyRule.RequireHumanApprovalAbove ->
                    checkHumanApproval(rule, intent, violations)

                is PolicyRule.AllowedTokens ->
                    checkAllowedTokens(rule, intent, violations)

                is PolicyRule.AllowedChains ->
                    checkAllowedChains(rule, intent, violations)
            }
        }

        return if (violations.isEmpty()) PolicyEvaluationResult.Approved
        else PolicyEvaluationResult.Denied(violations)
    }

    private fun checkMaxDebit(
        rule: PolicyRule,
        label: String,
        limit: MonetaryAmount,
        priorTotal: MonetaryAmount,
        intent: LedgerIntent,
        violations: MutableList<PolicyViolation>,
    ) {
        val intentDebitTotal = intent.lines
            .filter { it.entryType == EntryType.DEBIT }
            .fold(MonetaryAmount.ZERO) { acc, line -> acc + line.monetaryEntry.amount }

        val runningTotal = priorTotal + intentDebitTotal

        if (runningTotal > limit) {
            violations += PolicyViolation(
                rule = rule,
                message = "Debit total for $label ($runningTotal) exceeds limit ($limit)",
            )
        }
    }

    private fun checkForbiddenPair(
        rule: PolicyRule.ForbiddenAccountPair,
        intent: LedgerIntent,
        violations: MutableList<PolicyViolation>,
    ) {
        val hasDebitOnForbidden = intent.lines.any {
            it.entryType == EntryType.DEBIT && it.accountId == rule.debitAccount
        }
        val hasCreditOnForbidden = intent.lines.any {
            it.entryType == EntryType.CREDIT && it.accountId == rule.creditAccount
        }

        if (hasDebitOnForbidden && hasCreditOnForbidden) {
            violations += PolicyViolation(
                rule = rule,
                message = "Forbidden account pair: debit on ${rule.debitAccount.value} " +
                    "and credit on ${rule.creditAccount.value} are not allowed together",
            )
        }
    }

    private fun checkHumanApproval(
        rule: PolicyRule.RequireHumanApprovalAbove,
        intent: LedgerIntent,
        violations: MutableList<PolicyViolation>,
    ) {
        val offendingLines = intent.lines.filter {
            it.entryType == EntryType.DEBIT && it.monetaryEntry.amount > rule.threshold
        }

        if (offendingLines.isNotEmpty()) {
            val maxAmount = offendingLines.maxByOrNull { it.monetaryEntry.amount.value }!!.monetaryEntry.amount
            violations += PolicyViolation(
                rule = rule,
                message = "Debit of $maxAmount exceeds human-approval threshold (${rule.threshold}); " +
                    "manual approval is required",
            )
        }
    }

    private fun checkAllowedTokens(
        rule: PolicyRule.AllowedTokens,
        intent: LedgerIntent,
        violations: MutableList<PolicyViolation>,
    ) {
        val offendingTokens = intent.lines
            .mapNotNull { line ->
                val entry = line.monetaryEntry
                if (entry is OnChainEntry && entry.token !in rule.tokens) entry.token else null
            }
            .toSet()

        if (offendingTokens.isNotEmpty()) {
            violations += PolicyViolation(
                rule = rule,
                message = "Token(s) $offendingTokens are not in the allowed set ${rule.tokens}",
            )
        }
    }

    private fun checkAllowedChains(
        rule: PolicyRule.AllowedChains,
        intent: LedgerIntent,
        violations: MutableList<PolicyViolation>,
    ) {
        val offendingChains = intent.lines
            .mapNotNull { line ->
                val entry = line.monetaryEntry
                if (entry is OnChainEntry && entry.chainId !in rule.chains) entry.chainId else null
            }
            .toSet()

        if (offendingChains.isNotEmpty()) {
            violations += PolicyViolation(
                rule = rule,
                message = "Chain(s) $offendingChains are not in the allowed set ${rule.chains}",
            )
        }
    }
}
