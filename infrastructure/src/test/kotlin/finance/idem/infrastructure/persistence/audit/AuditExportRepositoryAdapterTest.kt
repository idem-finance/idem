package finance.idem.infrastructure.persistence.audit

import finance.idem.application.audit.AuditEntry
import finance.idem.application.audit.AuditEntryType
import finance.idem.application.audit.AuditExportRecord
import finance.idem.application.audit.ExportAuditLogQuery
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.AgentAuditEvent
import finance.idem.core.agentic.AgentContext
import finance.idem.core.tenant.TenantConfigRepository
import finance.idem.infrastructure.SharedPostgresTestBase
import finance.idem.infrastructure.persistence.PersistenceTestConfig
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    AuditExportRepositoryAdapter::class,
    AuditRepositoryAdapter::class,
    AgentAuditRepositoryAdapter::class,
    AuditConfig::class,
    PersistenceTestConfig::class,
)
class AuditExportRepositoryAdapterTest : SharedPostgresTestBase() {
    @Autowired
    lateinit var adapter: AuditExportRepositoryAdapter

    @Autowired
    lateinit var auditRepositoryAdapter: AuditRepositoryAdapter

    @MockitoBean
    lateinit var tenantConfigRepository: TenantConfigRepository

    @BeforeEach
    fun stubTenantConfigLookup() {
        Mockito.lenient().whenever(tenantConfigRepository.findByTenantId(any())).thenReturn(null)
    }

    @Autowired
    lateinit var agentAuditRepositoryAdapter: AgentAuditRepositoryAdapter

    @Autowired
    lateinit var entityManager: EntityManager

    private val tenantA = TenantId.generate()
    private val tenantB = TenantId.generate()

    private val epochFrom = Instant.parse("2020-01-01T00:00:00Z")
    private val epochTo = Instant.parse("2030-01-01T00:00:00Z")

    private fun auditEntry(tenantId: TenantId = tenantA) =
        AuditEntry(
            id = UUID.randomUUID(),
            transactionId = TransactionId.generate(),
            tenantId = tenantId,
            action = "POST_TRANSACTION",
            agentContext = null,
            createdBy = "sk_live_test",
            occurredAt = Instant.now(),
        )

    private fun agentContext(planId: WorkflowPlanId) =
        AgentContext(agentId = "agent-export", sessionId = "sess-export", workflowPlanId = planId)

    private fun flushAndClear() {
        entityManager.flush()
        entityManager.clear()
    }

    @Test
    fun `HUMAN type returns only audit_log records`() {
        val planId = WorkflowPlanId.generate()
        auditRepositoryAdapter.save(auditEntry())
        agentAuditRepositoryAdapter.save(AgentAuditEvent.pending(planId, tenantA, agentContext(planId), null))
        flushAndClear()

        val results = adapter.findForExport(ExportAuditLogQuery(tenantA, epochFrom, epochTo, AuditEntryType.HUMAN))

        assertEquals(1, results.size)
        assertEquals("TRANSACTION", results[0].entityType)
        assertEquals("POST_TRANSACTION", results[0].action)
    }

    @Test
    fun `AGENT type returns only agent_audit_events records`() {
        val planId = WorkflowPlanId.generate()
        auditRepositoryAdapter.save(auditEntry())
        agentAuditRepositoryAdapter.save(AgentAuditEvent.pending(planId, tenantA, agentContext(planId), null))
        flushAndClear()

        val results = adapter.findForExport(ExportAuditLogQuery(tenantA, epochFrom, epochTo, AuditEntryType.AGENT))

        assertEquals(1, results.size)
        assertEquals("WORKFLOW", results[0].entityType)
        assertEquals("AGENT_ACTION_STARTED", results[0].action)
    }

    @Test
    fun `ALL type returns both tables merged and sorted by timestamp ascending`() {
        val earlier = Instant.now().minusSeconds(10)
        val later = Instant.now()

        val txEntry = auditEntry().copy(occurredAt = later)
        val planId = WorkflowPlanId.generate()
        val agentEvent =
            AgentAuditEvent
                .pending(planId, tenantA, agentContext(planId), null)
                .let { it.copy(occurredAt = earlier) }

        auditRepositoryAdapter.save(txEntry)
        agentAuditRepositoryAdapter.save(agentEvent)
        flushAndClear()

        val results = adapter.findForExport(ExportAuditLogQuery(tenantA, epochFrom, epochTo, AuditEntryType.ALL))

        assertTrue(results.size >= 2)
        val relevant = results.filter { it.entityType == "WORKFLOW" || it.entityType == "TRANSACTION" }
        val workflowIdx = relevant.indexOfFirst { it.entityType == "WORKFLOW" }
        val txIdx = relevant.indexOfFirst { it.entityType == "TRANSACTION" }
        assertTrue(workflowIdx < txIdx, "WORKFLOW (earlier) must appear before TRANSACTION (later)")
    }

    @Test
    fun `tenant isolation — only own tenant rows returned`() {
        auditRepositoryAdapter.save(auditEntry(tenantA))
        auditRepositoryAdapter.save(auditEntry(tenantB))
        flushAndClear()

        val resultsA = adapter.findForExport(ExportAuditLogQuery(tenantA, epochFrom, epochTo, AuditEntryType.HUMAN))
        val resultsB = adapter.findForExport(ExportAuditLogQuery(tenantB, epochFrom, epochTo, AuditEntryType.HUMAN))

        assertTrue(resultsA.all { it.entityType == "TRANSACTION" })
        assertTrue(resultsB.all { it.entityType == "TRANSACTION" })
        assertEquals(1, resultsA.size, "Tenant A should see exactly its own 1 row")
        assertEquals(1, resultsB.size, "Tenant B should see exactly its own 1 row")
    }

    @Test
    fun `date range filter excludes records outside the window`() {
        val insideRange = Instant.parse("2026-06-01T12:00:00Z")
        val outsideRange = Instant.parse("2025-01-01T12:00:00Z")

        auditRepositoryAdapter.save(auditEntry().copy(occurredAt = insideRange))
        auditRepositoryAdapter.save(auditEntry().copy(occurredAt = outsideRange))
        flushAndClear()

        val narrowFrom = Instant.parse("2026-01-01T00:00:00Z")
        val narrowTo = Instant.parse("2026-12-31T23:59:59Z")
        val results = adapter.findForExport(ExportAuditLogQuery(tenantA, narrowFrom, narrowTo, AuditEntryType.HUMAN))

        assertEquals(1, results.size)
        assertEquals(insideRange, results[0].timestamp)
    }

    @Test
    fun `hmacSignature field is populated in each record`() {
        auditRepositoryAdapter.save(auditEntry())
        flushAndClear()

        val results = adapter.findForExport(ExportAuditLogQuery(tenantA, epochFrom, epochTo, AuditEntryType.HUMAN))

        assertTrue(results.isNotEmpty())
        assertTrue(results[0].hmacSignature.isNotBlank())
    }
}
