package finance.idem.infrastructure.service

import finance.idem.application.audit.AuditEntryType
import finance.idem.application.audit.AuditExportRecord
import finance.idem.application.audit.ExportAuditLogQuery
import finance.idem.application.port.AuditExportRepository
import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class ExportAuditLogServiceTest {

    private val tenantId = TenantId.generate()
    private val from = Instant.parse("2026-01-01T00:00:00Z")
    private val to = Instant.parse("2026-06-30T23:59:59Z")

    private var capturedQuery: ExportAuditLogQuery? = null
    private var stubbedResult: List<AuditExportRecord> = emptyList()

    private val repo: AuditExportRepository = object : AuditExportRepository {
        override fun findForExport(query: ExportAuditLogQuery): List<AuditExportRecord> {
            capturedQuery = query
            return stubbedResult
        }
    }

    private val service = ExportAuditLogService(repo)

    private fun record(action: String) = AuditExportRecord(
        timestamp = Instant.now(),
        actor = "sk_live_test",
        action = action,
        entityType = "TRANSACTION",
        entityId = UUID.randomUUID(),
        intentDescription = null,
        hmacSignature = "abc123",
        outcome = null,
    )

    @Test
    fun `delegates query to repository and returns result`() {
        stubbedResult = listOf(record("POST_TRANSACTION"))
        val query = ExportAuditLogQuery(tenantId, from, to, AuditEntryType.HUMAN)

        val result = service.export(query)

        assertEquals(query, capturedQuery)
        assertEquals(stubbedResult, result)
    }

    @Test
    fun `returns repository result unchanged for AGENT type`() {
        stubbedResult = listOf(record("AGENT_ACTION_COMPLETED"))
        val query = ExportAuditLogQuery(tenantId, from, to, AuditEntryType.AGENT)

        val result = service.export(query)

        assertEquals(1, result.size)
        assertEquals("AGENT_ACTION_COMPLETED", result[0].action)
    }

    @Test
    fun `returns empty list when repository returns empty`() {
        val query = ExportAuditLogQuery(tenantId, from, to, AuditEntryType.ALL)

        val result = service.export(query)

        assertEquals(emptyList(), result)
    }
}
