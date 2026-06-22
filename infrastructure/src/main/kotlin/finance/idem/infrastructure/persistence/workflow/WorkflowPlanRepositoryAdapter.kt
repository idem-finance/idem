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

    // Trade-off: save() is called ~5 times per workflow execution (PLANNED, EXECUTING, per step,
    // COMMITTED), each time deleting all steps and re-inserting them. For MVP workflow sizes this
    // is acceptable. At scale (>~20 steps) or high concurrency, replace with targeted
    // updateStatus() + saveStep() methods to avoid O(N) churn per save call.
    @Transactional
    override fun save(plan: WorkflowPlan) {
        setTenantId(plan.tenantId)
        // Delete steps first to avoid duplicate key on the (workflow_plan_id, step_index) unique constraint.
        // JPA cascade flushes INSERTs before orphanRemoval DELETEs, so we pre-empt this by deleting via SQL.
        entityManager.createNativeQuery(
            "DELETE FROM workflow_plan_steps WHERE workflow_plan_id = '${plan.id.value}'"
        ).executeUpdate()
        entityManager.flush()
        entityManager.clear()
        jpaRepository.save(plan.toEntity())
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
