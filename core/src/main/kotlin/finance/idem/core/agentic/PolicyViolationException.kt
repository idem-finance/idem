package finance.idem.core.agentic

/**
 * Thrown by a use case when [PolicyGuard.evaluate] returns [PolicyEvaluationResult.Denied].
 *
 * Lives in core alongside [finance.idem.core.LedgerInvariantViolation] because it wraps
 * pure domain objects ([PolicyViolation]s) with no infrastructure dependency.
 *
 * [PolicyGuard] itself never throws — callers are responsible for converting a
 * [PolicyEvaluationResult.Denied] into this exception.
 */
class PolicyViolationException(
    val violations: List<PolicyViolation>,
) : RuntimeException(
        "Policy denied: ${violations.joinToString("; ") { it.message }}",
    )
