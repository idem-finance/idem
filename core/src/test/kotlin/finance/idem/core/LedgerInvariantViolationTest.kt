package finance.idem.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LedgerInvariantViolationTest {

    @Test
    fun `is a RuntimeException with the given message`() {
        val ex = assertThrows<LedgerInvariantViolation> {
            throw LedgerInvariantViolation("debit sum does not equal credit sum")
        }
        assertEquals("debit sum does not equal credit sum", ex.message)
        assertIs<RuntimeException>(ex)
    }
}
