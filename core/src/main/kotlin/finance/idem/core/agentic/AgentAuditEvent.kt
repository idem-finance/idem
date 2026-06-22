package finance.idem.core.agentic

import finance.idem.core.TenantId
import finance.idem.core.WorkflowPlanId
import java.time.Instant
import java.util.UUID

enum class AgentAuditStatus { PENDING, COMPLETED, FAILED }

data class AgentAuditEvent(
    val id: UUID,
    val workflowPlanId: WorkflowPlanId,
    val tenantId: TenantId,
    val agentContext: AgentContext,
    val status: AgentAuditStatus,
    val intent: String?,
    val outcome: String?,
    val occurredAt: Instant,
) {
    companion object {
        fun pending(
            workflowPlanId: WorkflowPlanId,
            tenantId: TenantId,
            agentContext: AgentContext,
            intent: String?,
        ): AgentAuditEvent = AgentAuditEvent(
            id = UUID.randomUUID(),
            workflowPlanId = workflowPlanId,
            tenantId = tenantId,
            agentContext = agentContext,
            status = AgentAuditStatus.PENDING,
            intent = intent,
            outcome = null,
            occurredAt = Instant.now(),
        )

        fun completed(
            workflowPlanId: WorkflowPlanId,
            tenantId: TenantId,
            agentContext: AgentContext,
            outcome: String,
        ): AgentAuditEvent = AgentAuditEvent(
            id = UUID.randomUUID(),
            workflowPlanId = workflowPlanId,
            tenantId = tenantId,
            agentContext = agentContext,
            status = AgentAuditStatus.COMPLETED,
            intent = agentContext.intent,
            outcome = outcome,
            occurredAt = Instant.now(),
        )

        fun failed(
            workflowPlanId: WorkflowPlanId,
            tenantId: TenantId,
            agentContext: AgentContext,
            outcome: String,
        ): AgentAuditEvent = AgentAuditEvent(
            id = UUID.randomUUID(),
            workflowPlanId = workflowPlanId,
            tenantId = tenantId,
            agentContext = agentContext,
            status = AgentAuditStatus.FAILED,
            intent = agentContext.intent,
            outcome = outcome,
            occurredAt = Instant.now(),
        )
    }
}
