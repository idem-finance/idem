package finance.idem.core.agentic

/**
 * A single rule that was violated during [PolicyGuard] evaluation.
 *
 * [rule] is the original [PolicyRule] instance that produced this violation,
 * allowing callers to pattern-match on rule type if needed.
 * [message] is a human-readable description suitable for audit logs and error responses.
 */
data class PolicyViolation(
    val rule: PolicyRule,
    val message: String,
)
