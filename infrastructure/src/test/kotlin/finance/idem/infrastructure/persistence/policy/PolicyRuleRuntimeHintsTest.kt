package finance.idem.infrastructure.persistence.policy

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.aot.hint.RuntimeHints

class PolicyRuleRuntimeHintsTest {
    @Test
    fun `registers a reflection hint for the native-query result type`() {
        val hints = RuntimeHints()

        PolicyRuleRuntimeHints().registerHints(hints, javaClass.classLoader)

        assertNotNull(hints.reflection().getTypeHint(PolicyRuleDataModel::class.java))
    }
}
