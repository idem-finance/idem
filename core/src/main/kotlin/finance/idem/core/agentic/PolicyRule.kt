package finance.idem.core.agentic

import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken

/**
 * A single rule that governs what an [AgentContext] may do to the ledger.
 * Rules are evaluated together by [PolicyGuard.evaluate].
 *
 * For amount-based rules, [MonetaryAmount] limits are compared against raw BigDecimal
 * values with no currency dimension. Callers must normalize all amounts to the same
 * unit before constructing a [LedgerIntent].
 */
sealed class PolicyRule {

    /**
     * Total DEBIT across all intent lines, added to [LedgerIntent.priorSessionDebitTotal],
     * must not exceed [limit].
     */
    data class MaxDebitPerSession(val limit: MonetaryAmount) : PolicyRule()

    /**
     * Total DEBIT across all intent lines, added to [LedgerIntent.priorHourlyDebitTotal],
     * must not exceed [limit].
     */
    data class MaxDebitPerHour(val limit: MonetaryAmount) : PolicyRule()

    /**
     * The intent may not simultaneously debit [debitAccount] and credit [creditAccount].
     * Both sides must be present in the intent lines for a violation to be raised.
     */
    data class ForbiddenAccountPair(
        val debitAccount: AccountId,
        val creditAccount: AccountId,
    ) : PolicyRule()

    /**
     * If any individual DEBIT line's amount strictly exceeds [threshold], a human
     * approval signal is required. The violation is raised immediately — the approval
     * mechanism is external to [PolicyGuard].
     */
    data class RequireHumanApprovalAbove(val threshold: MonetaryAmount) : PolicyRule()

    /**
     * Every on-chain line must use a token contained in [tokens].
     * Fiat lines are not evaluated by this rule.
     */
    data class AllowedTokens(val tokens: Set<StablecoinToken>) : PolicyRule()

    /**
     * Every on-chain line must use a chain contained in [chains].
     * Fiat lines are not evaluated by this rule.
     */
    data class AllowedChains(val chains: Set<ChainId>) : PolicyRule()
}
