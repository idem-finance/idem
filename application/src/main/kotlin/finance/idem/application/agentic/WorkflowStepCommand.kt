package finance.idem.application.agentic

import finance.idem.application.ledger.JournalLineRequest

data class WorkflowStepCommand(
    val idempotencyKey: String,
    val description: String = "",
    val lines: List<JournalLineRequest>,
    val metadata: Map<String, String> = emptyMap(),
)
