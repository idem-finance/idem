package finance.idem.core.agentic

import finance.idem.core.MonetaryAmount
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class PolicyViolationExceptionTest {

    private val rule = PolicyRule.MaxDebitPerSession(MonetaryAmount.of("100"))

    @Test
    fun `is a RuntimeException`() {
        val ex = PolicyViolationException(
            violations = listOf(PolicyViolation(rule = rule, message = "over limit")),
        )
        assertIs<RuntimeException>(ex)
    }

    @Test
    fun `message joins violation messages with semicolon separator`() {
        val ex = PolicyViolationException(
            violations = listOf(
                PolicyViolation(rule = rule, message = "first violation"),
                PolicyViolation(rule = PolicyRule.MaxDebitPerHour(MonetaryAmount.of("50")), message = "second violation"),
            ),
        )
        assertNotNull(ex.message)
        assertEquals("Policy denied: first violation; second violation", ex.message)
    }

    @Test
    fun `single violation produces message without trailing separator`() {
        val ex = PolicyViolationException(
            violations = listOf(PolicyViolation(rule = rule, message = "only violation")),
        )
        assertEquals("Policy denied: only violation", ex.message)
    }

    @Test
    fun `violations list is accessible from the exception`() {
        val violations = listOf(
            PolicyViolation(rule = rule, message = "v1"),
            PolicyViolation(rule = rule, message = "v2"),
        )
        val ex = PolicyViolationException(violations)
        assertEquals(violations, ex.violations)
    }
}
