package finance.idem.api.policy

import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.agentic.PolicyRule
import finance.idem.core.agentic.PolicyRuleRecord
import java.time.Instant

data class CreatePolicyRuleRequest(
    val type: String,
    val agentKeyPrefix: String? = null,
    val amount: String? = null,
    val debitAccountId: String? = null,
    val creditAccountId: String? = null,
    val tokens: List<String>? = null,
    val chains: List<String>? = null,
) {
    fun toRule(): PolicyRule = when (type.uppercase()) {
        "MAX_DEBIT_PER_SESSION" -> PolicyRule.MaxDebitPerSession(
            limit = MonetaryAmount.of(require("amount", amount)),
        )
        "MAX_DEBIT_PER_HOUR" -> PolicyRule.MaxDebitPerHour(
            limit = MonetaryAmount.of(require("amount", amount)),
        )
        "REQUIRE_HUMAN_APPROVAL_ABOVE" -> PolicyRule.RequireHumanApprovalAbove(
            threshold = MonetaryAmount.of(require("amount", amount)),
        )
        "FORBIDDEN_ACCOUNT_PAIR" -> PolicyRule.ForbiddenAccountPair(
            debitAccount = AccountId.of(require("debitAccountId", debitAccountId)),
            creditAccount = AccountId.of(require("creditAccountId", creditAccountId)),
        )
        "ALLOWED_TOKENS" -> PolicyRule.AllowedTokens(
            tokens = require("tokens", tokens).map { StablecoinToken.valueOf(it.uppercase()) }.toSet(),
        )
        "ALLOWED_CHAINS" -> PolicyRule.AllowedChains(
            chains = require("chains", chains).map { ChainId.valueOf(it.uppercase()) }.toSet(),
        )
        else -> throw IllegalArgumentException("Unknown policy rule type: $type")
    }

    private fun <T> require(field: String, value: T?): T =
        value ?: throw IllegalArgumentException("Field '$field' is required for rule type '$type'")
}

data class PolicyRuleResponse(
    val id: String,
    val type: String,
    val agentKeyPrefix: String?,
    val params: Map<String, Any>,
    val createdAt: Instant,
)

fun PolicyRuleRecord.toResponse(): PolicyRuleResponse =
    PolicyRuleResponse(
        id = id.value.toString(),
        type = rule.toTypeName(),
        agentKeyPrefix = agentKeyPrefix,
        params = rule.toParamMap(),
        createdAt = createdAt,
    )

private fun PolicyRule.toTypeName(): String = when (this) {
    is PolicyRule.MaxDebitPerSession -> "MAX_DEBIT_PER_SESSION"
    is PolicyRule.MaxDebitPerHour -> "MAX_DEBIT_PER_HOUR"
    is PolicyRule.RequireHumanApprovalAbove -> "REQUIRE_HUMAN_APPROVAL_ABOVE"
    is PolicyRule.ForbiddenAccountPair -> "FORBIDDEN_ACCOUNT_PAIR"
    is PolicyRule.AllowedTokens -> "ALLOWED_TOKENS"
    is PolicyRule.AllowedChains -> "ALLOWED_CHAINS"
}

private fun PolicyRule.toParamMap(): Map<String, Any> = when (this) {
    is PolicyRule.MaxDebitPerSession -> mapOf("amount" to limit.value.toPlainString())
    is PolicyRule.MaxDebitPerHour -> mapOf("amount" to limit.value.toPlainString())
    is PolicyRule.RequireHumanApprovalAbove -> mapOf("amount" to threshold.value.toPlainString())
    is PolicyRule.ForbiddenAccountPair -> mapOf(
        "debitAccountId" to debitAccount.value.toString(),
        "creditAccountId" to creditAccount.value.toString(),
    )
    is PolicyRule.AllowedTokens -> mapOf("tokens" to tokens.map { it.name })
    is PolicyRule.AllowedChains -> mapOf("chains" to chains.map { it.name })
}
