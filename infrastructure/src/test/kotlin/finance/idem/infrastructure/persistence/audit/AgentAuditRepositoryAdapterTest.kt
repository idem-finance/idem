package finance.idem.infrastructure.persistence.audit

import finance.idem.core.TenantId
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.AgentAuditEvent
import finance.idem.core.agentic.AgentAuditStatus
import finance.idem.core.agentic.AgentContext
import finance.idem.infrastructure.persistence.PersistenceTestConfig
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(AgentAuditRepositoryAdapter::class, AuditConfig::class, PersistenceTestConfig::class)
class AgentAuditRepositoryAdapterTest {

    companion object {
        private const val APP_ROLE = "idem_app_role"
        private const val APP_ROLE_PASSWORD = "app_role_pass"

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

        fun ensureRestrictedRole() {
            DriverManager.getConnection(postgres.jdbcUrl, "idem", "idem").use { conn ->
                conn.createStatement().use { stmt ->
                    try {
                        stmt.execute("CREATE ROLE $APP_ROLE NOSUPERUSER LOGIN PASSWORD '$APP_ROLE_PASSWORD'")
                    } catch (_: SQLException) {}
                    stmt.execute("GRANT CONNECT ON DATABASE idem_test TO $APP_ROLE")
                    stmt.execute("GRANT USAGE ON SCHEMA public TO $APP_ROLE")
                    stmt.execute("GRANT SELECT, INSERT ON agent_audit_events TO $APP_ROLE")
                }
            }
        }

        fun restrictedConn() = DriverManager.getConnection(postgres.jdbcUrl, APP_ROLE, APP_ROLE_PASSWORD)
    }

    @Autowired
    lateinit var adapter: AgentAuditRepositoryAdapter

    @Autowired
    lateinit var jpaRepository: AgentAuditEventJpaRepository

    @Autowired
    lateinit var entityManager: EntityManager

    private val tenantA = TenantId.generate()
    private val tenantB = TenantId.generate()

    private fun agentContext(planId: WorkflowPlanId) = AgentContext(
        agentId = "agent-1",
        sessionId = "sess-abc",
        workflowPlanId = planId,
        intent = "offramp",
    )

    @Test
    fun `save persists event with non-blank HMAC`() {
        val planId = WorkflowPlanId.generate()
        val event = AgentAuditEvent.pending(planId, tenantA, agentContext(planId), "offramp")

        adapter.save(event)
        entityManager.flush()
        entityManager.clear()

        entityManager.createNativeQuery("SET LOCAL app.tenant_id = '${tenantA.value}'").executeUpdate()
        val hmac = entityManager.createNativeQuery(
            "SELECT hmac FROM agent_audit_events WHERE id = '${event.id}'"
        ).singleResult as String

        assertTrue(hmac.isNotBlank())
        assertTrue(hmac.length >= 40, "HMAC should be base64-encoded ~44 chars")
    }

    @Test
    fun `two saves for same workflowPlanId — both rows persist (append-only)`() {
        val planId = WorkflowPlanId.generate()
        val ctx = agentContext(planId)

        val pending = AgentAuditEvent.pending(planId, tenantA, ctx, "offramp")
        val completed = AgentAuditEvent.completed(planId, tenantA, ctx, "done")

        adapter.save(pending)
        adapter.save(completed)
        entityManager.flush()
        entityManager.clear()

        entityManager.createNativeQuery("SET LOCAL app.tenant_id = '${tenantA.value}'").executeUpdate()
        val count = (entityManager.createNativeQuery(
            "SELECT COUNT(*) FROM agent_audit_events WHERE workflow_plan_id = '${planId.value}'"
        ).singleResult as Number).toLong()

        assertEquals(2L, count, "Both PENDING and COMPLETED rows must be present")

        val statuses = entityManager.createNativeQuery(
            "SELECT status FROM agent_audit_events WHERE workflow_plan_id = '${planId.value}' ORDER BY occurred_at"
        ).resultList.map { it as String }
        assertEquals(AgentAuditStatus.PENDING.name, statuses[0])
        assertEquals(AgentAuditStatus.COMPLETED.name, statuses[1])
    }

    @Test
    fun `RLS — each tenant sees only their own rows`() {
        val planA = WorkflowPlanId.generate()
        val planB = WorkflowPlanId.generate()
        adapter.save(AgentAuditEvent.pending(planA, tenantA, agentContext(planA), null))
        adapter.save(AgentAuditEvent.pending(planB, tenantB, agentContext(planB), null))

        entityManager.flush()
        entityManager.clear()

        val session = entityManager.unwrap(org.hibernate.Session::class.java)

        var countA = -1L
        session.doWork { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantA.value}'")
            val rs = conn.createStatement()
                .executeQuery("SELECT COUNT(*) FROM agent_audit_events WHERE tenant_id = '${tenantA.value}'")
            rs.next()
            countA = rs.getLong(1)
        }

        var countB = -1L
        session.doWork { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantB.value}'")
            val rs = conn.createStatement()
                .executeQuery("SELECT COUNT(*) FROM agent_audit_events WHERE tenant_id = '${tenantB.value}'")
            rs.next()
            countB = rs.getLong(1)
        }

        assertEquals(1L, countA, "Tenant A should see exactly its own 1 row")
        assertEquals(1L, countB, "Tenant B should see exactly its own 1 row")
    }

    @Test
    fun `findByFilter returns events matching sessionId`() {
        val planId = WorkflowPlanId.generate()
        val ctxA = AgentContext(agentId = "agent-1", sessionId = "sess-find", workflowPlanId = planId)
        val ctxB = AgentContext(agentId = "agent-2", sessionId = "sess-other", workflowPlanId = planId)

        adapter.save(AgentAuditEvent.pending(planId, tenantA, ctxA, "intent-a"))
        adapter.save(AgentAuditEvent.pending(planId, tenantA, ctxB, "intent-b"))
        entityManager.flush()
        entityManager.clear()

        val results = adapter.findByFilter(tenantId = tenantA, sessionId = "sess-find", limit = 10)

        assertEquals(1, results.size)
        assertEquals("sess-find", results[0].sessionId)
        assertEquals("agent-1", results[0].agentId)
        assertEquals("intent-a", results[0].intentPayload)
    }

    @Test
    fun `findByFilter toView — PENDING maps eventType and null completedAt`() {
        val planId = WorkflowPlanId.generate()
        val ctx = agentContext(planId)
        val event = AgentAuditEvent.pending(planId, tenantA, ctx, "test-intent")

        adapter.save(event)
        entityManager.flush()
        entityManager.clear()

        val results = adapter.findByFilter(tenantId = tenantA, limit = 10)

        val view = results.first { it.id == event.id }
        assertEquals("AGENT_ACTION_STARTED", view.eventType)
        assertEquals("PENDING", view.status)
        assertNull(view.completedAt)
        assertNotNull(view.hmacSignature)
        assertTrue(view.hmacSignature.isNotBlank())
    }

    @Test
    fun `findByFilter toView — COMPLETED maps eventType and completedAt`() {
        val planId = WorkflowPlanId.generate()
        val ctx = agentContext(planId)
        val event = AgentAuditEvent.completed(planId, tenantA, ctx, "done")

        adapter.save(event)
        entityManager.flush()
        entityManager.clear()

        val results = adapter.findByFilter(tenantId = tenantA, limit = 10)

        val view = results.first { it.id == event.id }
        assertEquals("AGENT_ACTION_COMPLETED", view.eventType)
        assertEquals("COMPLETED", view.status)
        assertNotNull(view.completedAt)
    }

    @Test
    fun `findByFilter respects limit`() {
        val planId = WorkflowPlanId.generate()
        val ctx = agentContext(planId)
        repeat(5) { adapter.save(AgentAuditEvent.pending(WorkflowPlanId.generate(), tenantA, ctx, null)) }
        entityManager.flush()
        entityManager.clear()

        val results = adapter.findByFilter(tenantId = tenantA, limit = 2)
        assertEquals(2, results.size)
    }

    @Test
    fun `findByFilter with no filters returns all events for tenant ordered desc`() {
        val before = Instant.now().minusSeconds(5)
        val planId = WorkflowPlanId.generate()
        val ctx = agentContext(planId)
        adapter.save(AgentAuditEvent.pending(planId, tenantA, ctx, null))
        adapter.save(AgentAuditEvent.completed(planId, tenantA, ctx, "done"))
        entityManager.flush()
        entityManager.clear()

        val results = adapter.findByFilter(tenantId = tenantA, from = before, limit = 10)
        assertTrue(results.size >= 2)
        assertTrue(results[0].occurredAt >= results[1].occurredAt)
    }

    @Test
    fun `agent_audit_events is append-only — UPDATE denied for non-superuser role`() {
        ensureRestrictedRole()
        val planId = WorkflowPlanId.generate()
        val event = AgentAuditEvent.pending(planId, tenantA, agentContext(planId), null)
        adapter.save(event)
        entityManager.flush()

        restrictedConn().use { conn ->
            conn.autoCommit = false
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantA.value}'")
            assertThrows<SQLException>("Non-superuser UPDATE must be denied") {
                conn.createStatement().executeUpdate(
                    "UPDATE agent_audit_events SET status = 'TAMPERED' WHERE id = '${event.id}'"
                )
            }
            conn.rollback()
        }
    }
}
