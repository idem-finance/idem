package finance.idem.core.agentic

import finance.idem.core.MonetaryAmount
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PolicyEvaluationResultTest {
    private val anyRule = PolicyRule.MaxDebitPerSession(MonetaryAmount.of("100"))
    private val anyViolation = PolicyViolation(rule = anyRule, message = "over limit")

    @Test
    fun `Approved is a singleton`() {
        assertSame(PolicyEvaluationResult.Approved, PolicyEvaluationResult.Approved)
    }

    @Test
    fun `Denied constructs successfully with one violation`() {
        val result = PolicyEvaluationResult.Denied(listOf(anyViolation))
        assertIs<PolicyEvaluationResult.Denied>(result)
        assertEquals(1, result.violations.size)
        assertEquals(anyViolation, result.violations.first())
    }

    @Test
    fun `Denied with empty violations throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            PolicyEvaluationResult.Denied(emptyList())
        }
    }

    @Test
    fun `when on PolicyEvaluationResult is exhaustive without else`() {
        val approved: PolicyEvaluationResult = PolicyEvaluationResult.Approved
        val denied: PolicyEvaluationResult = PolicyEvaluationResult.Denied(listOf(anyViolation))

        var approvedBranch = false
        var deniedBranch = false

        when (approved) {
            is PolicyEvaluationResult.Approved -> approvedBranch = true
            is PolicyEvaluationResult.Denied -> deniedBranch = true
        }
        assertTrue(approvedBranch)

        when (denied) {
            is PolicyEvaluationResult.Approved -> approvedBranch = false
            is PolicyEvaluationResult.Denied -> deniedBranch = true
        }
        assertTrue(deniedBranch)
    }
}
