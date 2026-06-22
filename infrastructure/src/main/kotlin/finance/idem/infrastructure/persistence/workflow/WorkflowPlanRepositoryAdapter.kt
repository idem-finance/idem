package finance.idem.infrastructure.persistence.workflow

import finance.idem.application.port.WorkflowPlanRepository
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.AgentContext
import finance.idem.core.agentic.WorkflowPlan
import finance.idem.core.agentic.WorkflowPlanStatus
import finance.idem.core.agentic.WorkflowPlanStep
import finance.idem.core.agentic.WorkflowStepStatus
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

    private fun setTenantId(tenantId: TenantId) {
        entityManager.createNativeQuery("SET LOCAL app.tenant_id = '${tenantId.value}'")
            .executeUpdate()
    }

    @Transactional
    override fun insert(plan: WorkflowPlan) {
        setTenantId(plan.tenantId)
        jpaRepository.save(plan.toEntity())
    }

    @Transactional
    override fun updateStatus(id: WorkflowPlanId, tenantId: TenantId, status: WorkflowPlanStatus, committedAt: Instant?) {
        setTenantId(tenantId)
        if (committedAt != null) {
            entityManager.createNativeQuery(
                "UPDATE workflow_plans SET status = '${status.name}', committed_at = '${committedAt}' WHERE id = '${id.value}' AND tenant_id = '${tenantId.value}'"
            ).executeUpdate()
        } else {
            entityManager.createNativeQuery(
                "UPDATE workflow_plans SET status = '${status.name}' WHERE id = '${id.value}' AND tenant_id = '${tenantId.value}'"
            ).executeUpdate()
        }
    }

    @Transactional
    override fun updateStep(id: WorkflowPlanId, tenantId: TenantId, step: WorkflowPlanStep) {
        setTenantId(tenantId)
        val txIdValue = step.transactionId
        val txIdSql = if (txIdValue != null) "'${txIdValue.value}'" else "NULL"
        entityManager.createNativeQuery(
            "UPDATE workflow_plan_steps SET status = '${step.status.name}', transaction_id = $txIdSql WHERE workflow_plan_id = '${id.value}' AND step_index = ${step.stepIndex} AND tenant_id = '${tenantId.value}'"
        ).executeUpdate()
    }

    @Transactional(readOnly = true)
    override fun findById(id: WorkflowPlanId, tenantId: TenantId): WorkflowPlan? {
        setTenantId(tenantId)
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
        status = WorkflowPlanStatus.valueOf(status),
        steps = steps
            .sortedBy { it.stepIndex }
            .map { it.toDomain() },
        occurredAt = occurredAt,
        committedAt = committedAt,
    )

private fun WorkflowPlanStepDataModel.toDomain(): WorkflowPlanStep =
    WorkflowPlanStep(
        stepIndex = stepIndex,
        idempotencyKey = idempotencyKey,
        status = WorkflowStepStatus.valueOf(status),
        transactionId = transactionId?.let { TransactionId(it) },
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
        occurredAt = occurredAt,
        committedAt = committedAt,
    )
    steps.map { it.toEntity(entity) }.forEach { entity.steps.add(it) }
    return entity
}

private fun WorkflowPlanStep.toEntity(plan: WorkflowPlanDataModel): WorkflowPlanStepDataModel =
    WorkflowPlanStepDataModel(
        id = UUID.randomUUID(),
        workflowPlan = plan,
        tenantId = plan.tenantId,
        stepIndex = stepIndex,
        idempotencyKey = idempotencyKey,
        status = status.name,
        transactionId = transactionId?.value,
    )
