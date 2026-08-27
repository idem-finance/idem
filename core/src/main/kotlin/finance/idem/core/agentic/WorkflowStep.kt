package finance.idem.core.agentic

import finance.idem.core.TransactionId
import java.time.Instant
import java.util.UUID

// REORGED is distinct from ROLLED_BACK: the step's on-chain settlement was invalidated by a
// chain reorg (ReorgReversalService), not compensated by an operator/agent-initiated rollback.
enum class StepStatus { PENDING, EXECUTED, ROLLED_BACK, FAILED, REORGED }

data class WorkflowStep(
    val stepId: UUID,
    val stepOrder: Int,
    val description: String,
    val transactionId: TransactionId?,
    val status: StepStatus,
    val executedAt: Instant?,
    val compensatingTransactionId: TransactionId?,
)
