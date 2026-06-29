package finance.idem.api.compliance

import com.fasterxml.jackson.databind.ObjectMapper
import finance.idem.api.security.TestSecurityConfig
import finance.idem.application.audit.AuditEntryType
import finance.idem.application.audit.AuditExportRecord
import finance.idem.application.audit.ExportAuditLogQuery
import finance.idem.application.audit.ExportAuditLogUseCase
import finance.idem.core.TenantId
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@WebMvcTest(ComplianceController::class)
@Import(TestSecurityConfig::class)
class ComplianceControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockitoBean
    lateinit var exportAuditLogUseCase: ExportAuditLogUseCase

    private val tenantId = TenantId(UUID.randomUUID())

    private fun mockAuth(vararg scopes: String): TestingAuthenticationToken =
        TestingAuthenticationToken(tenantId, null, *scopes)

    private fun record(action: String, entityType: String = "TRANSACTION") = AuditExportRecord(
        timestamp = Instant.parse("2026-06-01T12:00:00Z"),
        actor = "sk_live_test",
        action = action,
        entityType = entityType,
        entityId = UUID.randomUUID(),
        intentDescription = null,
        hmacSignature = "base64hmachere",
        outcome = null,
    )

    @Test
    fun `missing COMPLIANCE_EXPORT scope returns 403`() {
        mockMvc.get("/api/v1/compliance/audit-export?from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z") {
            with(authentication(mockAuth("TRANSACTIONS_READ")))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `missing from parameter returns 400`() {
        mockMvc.get("/api/v1/compliance/audit-export?to=2026-12-31T23:59:59Z") {
            with(authentication(mockAuth("COMPLIANCE_EXPORT")))
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `missing to parameter returns 400`() {
        mockMvc.get("/api/v1/compliance/audit-export?from=2026-01-01T00:00:00Z") {
            with(authentication(mockAuth("COMPLIANCE_EXPORT")))
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `from after to returns 400`() {
        mockMvc.get("/api/v1/compliance/audit-export?from=2026-12-31T00:00:00Z&to=2026-01-01T00:00:00Z") {
            with(authentication(mockAuth("COMPLIANCE_EXPORT")))
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `valid request returns 200 with NDJSON content-type and disposition header`() {
        whenever(exportAuditLogUseCase.export(any())).thenReturn(listOf(record("POST_TRANSACTION")))

        val result = mockMvc.get(
            "/api/v1/compliance/audit-export?from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z"
        ) {
            with(authentication(mockAuth("COMPLIANCE_EXPORT")))
        }.andExpect {
            status { isOk() }
        }.andReturn()

        assertTrue(result.response.contentType?.startsWith("application/x-ndjson") == true)
        assertTrue(result.response.getHeader(HttpHeaders.CONTENT_DISPOSITION)?.contains("audit-") == true)
        assertTrue(result.response.getHeader(HttpHeaders.CONTENT_DISPOSITION)?.contains(".ndjson") == true)
    }

    @Test
    fun `response body contains one NDJSON line per record`() {
        val records = listOf(record("POST_TRANSACTION"), record("AGENT_ACTION_STARTED", "WORKFLOW"))
        whenever(exportAuditLogUseCase.export(any())).thenReturn(records)

        val result = mockMvc.get(
            "/api/v1/compliance/audit-export?from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z"
        ) {
            with(authentication(mockAuth("COMPLIANCE_EXPORT")))
        }.andExpect {
            status { isOk() }
        }.andReturn()

        val lines = result.response.contentAsString.trimEnd().split("\n")
        assertEquals(2, lines.size)
        lines.forEach { line ->
            val node = objectMapper.readTree(line)
            assertTrue(node.has("timestamp"))
            assertTrue(node.has("actor"))
            assertTrue(node.has("action"))
            assertTrue(node.has("entityType"))
            assertTrue(node.has("entityId"))
            assertTrue(node.has("hmacSignature"))
        }
    }

    @Test
    fun `type defaults to ALL when not specified`() {
        var capturedType: AuditEntryType? = null
        whenever(exportAuditLogUseCase.export(any())).thenAnswer { inv ->
            capturedType = (inv.arguments[0] as ExportAuditLogQuery).type
            emptyList<AuditExportRecord>()
        }

        mockMvc.get(
            "/api/v1/compliance/audit-export?from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z"
        ) {
            with(authentication(mockAuth("COMPLIANCE_EXPORT")))
        }.andExpect { status { isOk() } }

        assertEquals(AuditEntryType.ALL, capturedType)
    }

    @Test
    fun `empty record list returns 200 with empty body`() {
        whenever(exportAuditLogUseCase.export(any())).thenReturn(emptyList())

        val result = mockMvc.get(
            "/api/v1/compliance/audit-export?from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z"
        ) {
            with(authentication(mockAuth("COMPLIANCE_EXPORT")))
        }.andExpect {
            status { isOk() }
        }.andReturn()

        assertTrue(result.response.contentAsString.isBlank())
    }
}
