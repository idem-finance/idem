package finance.idem.mcp

data class RollbackWorkflowResult(
    val rollbackId: String,
    val compensatedSteps: List<CompensatedStepItem>,
    val status: String,
)

data class CompensatedStepItem(
    val stepOrder: Int,
    val description: String,
    val compensatingTransactionId: String?,
)
