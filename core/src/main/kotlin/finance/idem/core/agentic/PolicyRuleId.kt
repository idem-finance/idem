package finance.idem.core.agentic

import java.util.UUID

@JvmInline
value class PolicyRuleId(
    val value: UUID,
) {
    companion object {
        fun generate() = PolicyRuleId(UUID.randomUUID())
    }
}
