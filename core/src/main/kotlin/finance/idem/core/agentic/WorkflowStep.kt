package finance.idem.core.agentic

import finance.idem.core.TransactionId
import java.time.Instant
import java.util.UUID

enum class StepStatus { PENDING, EXECUTED, ROLLED_BACK, FAILED }

data class WorkflowStep(
    val stepId: UUID,
    val stepOrder: Int,
    val description: String,
    val transactionId: TransactionId?,
    val status: StepStatus,
    val executedAt: Instant?,
    val compensatingTransactionId: TransactionId?,
)
