package finance.idem.infrastructure.persistence.workflow

import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.AgentContext
import finance.idem.core.agentic.StepStatus
import finance.idem.core.agentic.WorkflowPlan
import finance.idem.core.agentic.WorkflowStatus
import finance.idem.infrastructure.persistence.PersistenceTestConfig
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(WorkflowPlanRepositoryAdapter::class, PersistenceTestConfig::class)
class WorkflowPlanRepositoryAdapterTest {
    companion object {
        @Container
        val postgres =
            PostgreSQLContainer("postgres:16")
                .withDatabaseName("idem_test")
                .withUsername("idem")
                .withPassword("idem")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired
    lateinit var adapter: WorkflowPlanRepositoryAdapter

    @Autowired
    lateinit var entityManager: EntityManager

    private val tenantA = TenantId.generate()
    private val tenantB = TenantId.generate()
    private val now = Instant.now()

    private fun agentContext(planId: WorkflowPlanId) =
        AgentContext(
            agentId = "agent-1",
            sessionId = "sess-abc",
            workflowPlanId = planId,
            intent = "offramp",
        )

    private fun plan(tenantId: TenantId = tenantA): WorkflowPlan {
        val planId = WorkflowPlanId.generate()
        return WorkflowPlan.create(
            id = planId,
            tenantId = tenantId,
            agentContext = agentContext(planId),
            stepDescriptions = listOf("step-0", "step-1"),
            createdAt = now,
        )
    }

    @Test
    fun `insert and findById round-trip preserves all fields`() {
        val original = plan()
        adapter.insert(original)

        entityManager.flush()
        entityManager.clear()

        val found = adapter.findById(original.id, tenantA)

        assertNotNull(found)
        assertEquals(original.id, found.id)
        assertEquals(tenantA, found.tenantId)
        assertEquals(WorkflowStatus.PLANNED, found.status)
        assertEquals(2, found.steps.size)
        assertEquals("step-0", found.steps[0].description)
        assertEquals("step-1", found.steps[1].description)
        assertNull(found.completedAt)
        assertNull(found.rolledBackAt)
        assertNull(found.rollbackReason)
    }

    @Test
    fun `updateStatus transitions plan status without touching steps`() {
        val original = plan()
        adapter.insert(original)
        entityManager.flush()
        entityManager.clear()

        adapter.updateStatus(original.id, tenantA, WorkflowStatus.EXECUTING)
        entityManager.flush()
        entityManager.clear()

        val found = adapter.findById(original.id, tenantA)

        assertNotNull(found)
        assertEquals(WorkflowStatus.EXECUTING, found.status)
        assertEquals(2, found.steps.size)
        assertEquals(StepStatus.PENDING, found.steps[0].status)
        assertEquals(StepStatus.PENDING, found.steps[1].status)
    }

    @Test
    fun `updateStep marks a single step EXECUTED with transactionId and executedAt`() {
        val original = plan()
        adapter.insert(original)
        entityManager.flush()
        entityManager.clear()

        val txId = TransactionId.generate()
        val executedStep = original.withStepExecuted(0, txId).steps[0]
        adapter.updateStep(original.id, tenantA, executedStep)
        entityManager.flush()
        entityManager.clear()

        val found = adapter.findById(original.id, tenantA)

        assertNotNull(found)
        assertEquals(StepStatus.EXECUTED, found.steps[0].status)
        assertEquals(txId, found.steps[0].transactionId)
        assertNotNull(found.steps[0].executedAt)
        assertEquals(StepStatus.PENDING, found.steps[1].status)
    }

    @Test
    fun `completedAt is persisted via updateStatus`() {
        val original = plan()
        adapter.insert(original)
        entityManager.flush()
        entityManager.clear()

        val completedAt = Instant.now()
        adapter.updateStatus(original.id, tenantA, WorkflowStatus.COMMITTED, completedAt = completedAt)
        entityManager.flush()
        entityManager.clear()

        val found = adapter.findById(original.id, tenantA)

        assertNotNull(found)
        assertEquals(WorkflowStatus.COMMITTED, found.status)
        assertNotNull(found.completedAt)
    }

    @Test
    fun `rolledBackAt and rollbackReason are persisted via updateStatus`() {
        val original = plan()
        adapter.insert(original)
        entityManager.flush()
        entityManager.clear()

        val rolledBackAt = Instant.now()
        adapter.updateStatus(
            original.id,
            tenantA,
            WorkflowStatus.ROLLED_BACK,
            rolledBackAt = rolledBackAt,
            rollbackReason = "compliance review",
        )
        entityManager.flush()
        entityManager.clear()

        val found = adapter.findById(original.id, tenantA)

        assertNotNull(found)
        assertEquals(WorkflowStatus.ROLLED_BACK, found.status)
        assertNotNull(found.rolledBackAt)
        assertEquals("compliance review", found.rollbackReason)
    }

    @Test
    fun `compensatingTransactionId is persisted after rollback step update`() {
        val original = plan()
        adapter.insert(original)
        entityManager.flush()
        entityManager.clear()

        val txId = TransactionId.generate()
        val compensatingTxId = TransactionId.generate()
        val executedPlan = original.withStepExecuted(0, txId)
        adapter.updateStep(original.id, tenantA, executedPlan.steps[0])
        val rolledBackPlan = executedPlan.withStepRolledBack(0, compensatingTxId)
        adapter.updateStep(original.id, tenantA, rolledBackPlan.steps[0])
        entityManager.flush()
        entityManager.clear()

        val found = adapter.findById(original.id, tenantA)

        assertNotNull(found)
        assertEquals(StepStatus.ROLLED_BACK, found.steps[0].status)
        assertEquals(compensatingTxId, found.steps[0].compensatingTransactionId)
    }

    @Test
    fun `findById returns null for unknown plan`() {
        val found = adapter.findById(WorkflowPlanId.generate(), tenantA)
        assertNull(found)
    }

    @Test
    fun `RLS — tenant A cannot see tenant B plans`() {
        val planB = plan(tenantB)
        adapter.insert(planB)
        entityManager.flush()
        entityManager.clear()

        val found = adapter.findById(planB.id, tenantA)
        assertNull(found, "Tenant A must not see tenant B's plan")
    }
}
