package finance.idem.core.agentic

/**
 * The outcome of a [PolicyGuard.evaluate] call.
 *
 * [Approved] — all rules passed; the intent may proceed to execution.
 * [Denied]   — one or more rules failed; [violations] is never empty.
 */
sealed class PolicyEvaluationResult {

    data object Approved : PolicyEvaluationResult()

    data class Denied(val violations: List<PolicyViolation>) : PolicyEvaluationResult() {
        init {
            require(violations.isNotEmpty()) { "Denied must carry at least one violation" }
        }
    }
}
