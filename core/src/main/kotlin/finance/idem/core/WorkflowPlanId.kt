package finance.idem.core

import java.util.UUID

@JvmInline
value class WorkflowPlanId(
    val value: UUID,
) {
    companion object {
        fun generate(): WorkflowPlanId = WorkflowPlanId(UUID.randomUUID())

        fun of(value: String): WorkflowPlanId = WorkflowPlanId(UUID.fromString(value))
    }
}
