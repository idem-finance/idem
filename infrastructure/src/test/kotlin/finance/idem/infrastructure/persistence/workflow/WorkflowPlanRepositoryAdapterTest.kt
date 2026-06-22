package finance.idem.infrastructure.persistence.workflow

import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.AgentContext
import finance.idem.core.agentic.WorkflowPlan
import finance.idem.core.agentic.WorkflowPlanStatus
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
        val postgres = PostgreSQLContainer("postgres:16")
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

    private fun agentContext(planId: WorkflowPlanId) = AgentContext(
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
            stepIdempotencyKeys = listOf("step-0", "step-1"),
            occurredAt = now,
        )
    }

    @Test
    fun `save and findById round-trip preserves all fields`() {
        val original = plan()
        adapter.save(original)

        entityManager.flush()
        entityManager.clear()

        val found = adapter.findById(original.id, tenantA)

        assertNotNull(found)
        assertEquals(original.id, found.id)
        assertEquals(tenantA, found.tenantId)
        assertEquals(WorkflowPlanStatus.PLANNED, found.status)
        assertEquals(2, found.steps.size)
        assertEquals("step-0", found.steps[0].idempotencyKey)
        assertEquals("step-1", found.steps[1].idempotencyKey)
        assertNull(found.committedAt)
    }

    @Test
    fun `second save with updated status overwrites first via JPA merge`() {
        val original = plan()
        adapter.save(original)
        entityManager.flush()
        entityManager.clear()

        val updated = original
            .withStatus(WorkflowPlanStatus.EXECUTING)
            .withStepExecuted(0, TransactionId.generate())
        adapter.save(updated)
        entityManager.flush()
        entityManager.clear()

        val found = adapter.findById(original.id, tenantA)

        assertNotNull(found)
        assertEquals(WorkflowPlanStatus.EXECUTING, found.status)
        assertEquals(finance.idem.core.agentic.WorkflowStepStatus.EXECUTED, found.steps[0].status)
        assertNotNull(found.steps[0].transactionId)
    }

    @Test
    fun `committedAt is persisted when plan is COMMITTED`() {
        val original = plan()
        adapter.save(original)
        entityManager.flush()
        entityManager.clear()

        val committedAt = Instant.now()
        val committed = original
            .withStatus(WorkflowPlanStatus.COMMITTED)
            .copy(committedAt = committedAt)
        adapter.save(committed)
        entityManager.flush()
        entityManager.clear()

        val found = adapter.findById(original.id, tenantA)

        assertNotNull(found)
        assertEquals(WorkflowPlanStatus.COMMITTED, found.status)
        assertNotNull(found.committedAt)
    }

    @Test
    fun `findById returns null for unknown plan`() {
        val found = adapter.findById(WorkflowPlanId.generate(), tenantA)
        assertNull(found)
    }

    @Test
    fun `RLS — tenant A cannot see tenant B plans`() {
        val planB = plan(tenantB)
        adapter.save(planB)
        entityManager.flush()
        entityManager.clear()

        val found = adapter.findById(planB.id, tenantA)
        assertNull(found, "Tenant A must not see tenant B's plan")
    }
}
