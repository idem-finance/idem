package finance.idem.infrastructure.persistence.workflow

import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.AgentContext
import finance.idem.core.agentic.StepStatus
import finance.idem.core.agentic.WorkflowPlan
import finance.idem.core.agentic.WorkflowPlanRepository
import finance.idem.core.agentic.WorkflowStatus
import finance.idem.core.agentic.WorkflowStep
import finance.idem.infrastructure.persistence.setRlsTenantId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Component
class WorkflowPlanRepositoryAdapter(
    private val jpaRepository: WorkflowPlanJpaRepository,
    private val entityManager: EntityManager,
) : WorkflowPlanRepository {

    @Transactional
    override fun insert(plan: WorkflowPlan) {
        entityManager.setRlsTenantId(plan.tenantId)
        jpaRepository.save(plan.toEntity())
    }

    @Transactional
    override fun updateStatus(
        id: WorkflowPlanId,
        tenantId: TenantId,
        status: WorkflowStatus,
        completedAt: Instant?,
        rolledBackAt: Instant?,
        rollbackReason: String?,
    ) {
        entityManager.setRlsTenantId(tenantId)
        val setParts = mutableListOf("status = '${status.name}'")
        if (completedAt != null) setParts += "completed_at = '$completedAt'"
        if (rolledBackAt != null) setParts += "rolled_back_at = '$rolledBackAt'"
        if (rollbackReason != null) setParts += "rollback_reason = '${rollbackReason.replace("'", "''")}'"
        val sql = "UPDATE workflow_plans SET ${setParts.joinToString(", ")} WHERE id = '${id.value}' AND tenant_id = '${tenantId.value}'"
        entityManager.createNativeQuery(sql).executeUpdate()
    }

    @Transactional
    override fun updateStep(id: WorkflowPlanId, tenantId: TenantId, step: WorkflowStep) {
        entityManager.setRlsTenantId(tenantId)
        val txIdSql = step.transactionId?.let { "'${it.value}'" } ?: "NULL"
        val executedAtSql = step.executedAt?.let { "'$it'" } ?: "NULL"
        val compensatingTxIdSql = step.compensatingTransactionId?.let { "'${it.value}'" } ?: "NULL"
        entityManager.createNativeQuery(
            "UPDATE workflow_steps SET status = '${step.status.name}', transaction_id = $txIdSql, executed_at = $executedAtSql, compensating_transaction_id = $compensatingTxIdSql WHERE workflow_plan_id = '${id.value}' AND step_order = ${step.stepOrder} AND tenant_id = '${tenantId.value}'"
        ).executeUpdate()
    }

    @Transactional(readOnly = true)
    override fun findById(id: WorkflowPlanId, tenantId: TenantId): WorkflowPlan? {
        entityManager.setRlsTenantId(tenantId)
        return jpaRepository.findByIdAndTenantId(id.value, tenantId.value)?.toDomain()
    }
}

// ── Entity → Domain ──────────────────────────────────────────────────────────

private fun WorkflowPlanDataModel.toDomain(): WorkflowPlan =
    WorkflowPlan.reconstitute(
        id = WorkflowPlanId(id),
        tenantId = TenantId(tenantId),
        agentContext = AgentContext(
            agentId = agentId,
            sessionId = sessionId,
            workflowPlanId = WorkflowPlanId(id),
            intent = intent,
        ),
        status = WorkflowStatus.valueOf(status),
        steps = steps
            .sortedBy { it.stepOrder }
            .map { it.toDomain() },
        createdAt = createdAt,
        completedAt = completedAt,
        rolledBackAt = rolledBackAt,
        rollbackReason = rollbackReason,
    )

private fun WorkflowStepDataModel.toDomain(): WorkflowStep =
    WorkflowStep(
        stepId = id,
        stepOrder = stepOrder,
        description = description,
        transactionId = transactionId?.let { TransactionId(it) },
        status = StepStatus.valueOf(status),
        executedAt = executedAt,
        compensatingTransactionId = compensatingTransactionId?.let { TransactionId(it) },
    )

// ── Domain → Entity ──────────────────────────────────────────────────────────

private fun WorkflowPlan.toEntity(): WorkflowPlanDataModel {
    val entity = WorkflowPlanDataModel(
        id = id.value,
        tenantId = tenantId.value,
        agentId = agentContext.agentId,
        sessionId = agentContext.sessionId,
        intent = agentContext.intent,
        status = status.name,
        createdAt = createdAt,
        completedAt = completedAt,
        rolledBackAt = rolledBackAt,
        rollbackReason = rollbackReason,
    )
    steps.map { it.toEntity(entity) }.forEach { entity.steps.add(it) }
    return entity
}

private fun WorkflowStep.toEntity(plan: WorkflowPlanDataModel): WorkflowStepDataModel =
    WorkflowStepDataModel(
        id = stepId,
        workflowPlan = plan,
        tenantId = plan.tenantId,
        stepOrder = stepOrder,
        description = description,
        status = status.name,
        transactionId = transactionId?.value,
        executedAt = executedAt,
        compensatingTransactionId = compensatingTransactionId?.value,
    )
