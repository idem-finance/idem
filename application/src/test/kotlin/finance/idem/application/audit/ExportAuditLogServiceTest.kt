package finance.idem.application.audit

import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AuditExportTypesTest {

    private val tenantId = TenantId.generate()
    private val from = Instant.parse("2026-01-01T00:00:00Z")
    private val to = Instant.parse("2026-12-31T23:59:59Z")
    private val entityId = UUID.randomUUID()

    @Test
    fun `AuditExportRecord holds all fields`() {
        val record = AuditExportRecord(
            timestamp = from,
            actor = "sk_live_test",
            action = "POST_TRANSACTION",
            entityType = "TRANSACTION",
            entityId = entityId,
            intentDescription = "offramp",
            hmacSignature = "abc123",
            outcome = null,
        )

        assertEquals(from, record.timestamp)
        assertEquals("sk_live_test", record.actor)
        assertEquals("POST_TRANSACTION", record.action)
        assertEquals("TRANSACTION", record.entityType)
        assertEquals(entityId, record.entityId)
        assertEquals("offramp", record.intentDescription)
        assertEquals("abc123", record.hmacSignature)
        assertEquals(null, record.outcome)
    }

    @Test
    fun `AuditExportRecord equality is value-based`() {
        val r1 = AuditExportRecord(from, "actor", "ACTION", "TRANSACTION", entityId, null, "sig", null)
        val r2 = AuditExportRecord(from, "actor", "ACTION", "TRANSACTION", entityId, null, "sig", null)
        assertEquals(r1, r2)
    }

    @Test
    fun `AuditExportRecord copy works`() {
        val original = AuditExportRecord(from, "actor", "ACTION", "TRANSACTION", entityId, null, "sig", null)
        val copy = original.copy(outcome = "SUCCESS")
        assertEquals("SUCCESS", copy.outcome)
        assertEquals(original.action, copy.action)
    }

    @Test
    fun `ExportAuditLogQuery holds all fields`() {
        val query = ExportAuditLogQuery(tenantId, from, to, AuditEntryType.ALL)
        assertEquals(tenantId, query.tenantId)
        assertEquals(from, query.from)
        assertEquals(to, query.to)
        assertEquals(AuditEntryType.ALL, query.type)
    }

    @Test
    fun `ExportAuditLogQuery equality is value-based`() {
        val q1 = ExportAuditLogQuery(tenantId, from, to, AuditEntryType.HUMAN)
        val q2 = ExportAuditLogQuery(tenantId, from, to, AuditEntryType.HUMAN)
        val q3 = ExportAuditLogQuery(tenantId, from, to, AuditEntryType.AGENT)
        assertEquals(q1, q2)
        assertNotEquals(q1, q3)
    }

    @Test
    fun `AuditEntryType has three values`() {
        val values = AuditEntryType.entries
        assertEquals(3, values.size)
        assertEquals(AuditEntryType.HUMAN, AuditEntryType.valueOf("HUMAN"))
        assertEquals(AuditEntryType.AGENT, AuditEntryType.valueOf("AGENT"))
        assertEquals(AuditEntryType.ALL, AuditEntryType.valueOf("ALL"))
    }
}
