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
     * If configured and the intent contains no on-chain entries at all, a violation is raised —
     * a token allowlist implies on-chain intent; fiat-only transactions are not permitted when
     * a token allowlist is active.
     */
    data class AllowedTokens(val tokens: Set<StablecoinToken>) : PolicyRule()

    /**
     * Every on-chain line must use a chain contained in [chains].
     * If configured and the intent contains no on-chain entries at all, a violation is raised —
     * a chain allowlist implies on-chain intent; fiat-only transactions are not permitted when
     * a chain allowlist is active.
     */
    data class AllowedChains(val chains: Set<ChainId>) : PolicyRule()

    /** Canonical type name for persistence and API serialization. */
    fun typeName(): String = when (this) {
        is MaxDebitPerSession -> "MAX_DEBIT_PER_SESSION"
        is MaxDebitPerHour -> "MAX_DEBIT_PER_HOUR"
        is RequireHumanApprovalAbove -> "REQUIRE_HUMAN_APPROVAL_ABOVE"
        is ForbiddenAccountPair -> "FORBIDDEN_ACCOUNT_PAIR"
        is AllowedTokens -> "ALLOWED_TOKENS"
        is AllowedChains -> "ALLOWED_CHAINS"
    }

    /** Rule parameters as a plain map — suitable for JSONB storage and API responses. */
    fun params(): Map<String, Any> = when (this) {
        is MaxDebitPerSession -> mapOf("amount" to limit.value.toPlainString())
        is MaxDebitPerHour -> mapOf("amount" to limit.value.toPlainString())
        is RequireHumanApprovalAbove -> mapOf("amount" to threshold.value.toPlainString())
        is ForbiddenAccountPair -> mapOf(
            "debitAccountId" to debitAccount.value.toString(),
            "creditAccountId" to creditAccount.value.toString(),
        )
        is AllowedTokens -> mapOf("tokens" to tokens.map { it.name })
        is AllowedChains -> mapOf("chains" to chains.map { it.name })
    }
}
