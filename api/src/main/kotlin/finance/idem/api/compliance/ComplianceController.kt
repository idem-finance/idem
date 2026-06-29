package finance.idem.api.compliance

import com.fasterxml.jackson.databind.ObjectMapper
import finance.idem.api.ledger.ErrorResponse
import finance.idem.application.audit.AuditEntryType
import finance.idem.application.audit.ExportAuditLogQuery
import finance.idem.application.audit.ExportAuditLogUseCase
import finance.idem.core.TenantId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@RestController
@RequestMapping("/api/v1/compliance")
@Tag(name = "Compliance", description = "Compliance and audit export endpoints")
class ComplianceController(
    private val exportAuditLogUseCase: ExportAuditLogUseCase,
    private val objectMapper: ObjectMapper,
) {

    companion object {
        private val NDJSON = MediaType("application", "x-ndjson")
        private val FILENAME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    }

    @GetMapping("/audit-export")
    @PreAuthorize("hasAuthority('COMPLIANCE_EXPORT')")
    @Operation(summary = "Export audit log as NDJSON for a time window")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "NDJSON stream of audit records"),
        ApiResponse(responseCode = "400", description = "Missing from/to or from after to"),
        ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
        ApiResponse(responseCode = "403", description = "API key does not have the COMPLIANCE_EXPORT scope"),
    )
    fun exportAudit(
        @Parameter(description = "Inclusive lower bound (ISO-8601 instant)", required = true)
        @RequestParam(required = false) from: Instant?,
        @Parameter(description = "Exclusive upper bound (ISO-8601 instant)", required = true)
        @RequestParam(required = false) to: Instant?,
        @Parameter(description = "Filter by entry type: HUMAN, AGENT, or ALL (default)")
        @RequestParam(defaultValue = "ALL") type: AuditEntryType,
    ): ResponseEntity<Any> {
        val tenantId = SecurityContextHolder.getContext().authentication?.principal as? TenantId
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        if (from == null || to == null) {
            return ResponseEntity.badRequest()
                .body(ErrorResponse("MISSING_PARAMETER", "from and to are required"))
        }

        if (from.isAfter(to)) {
            return ResponseEntity.badRequest()
                .body(ErrorResponse("INVALID_RANGE", "from must not be after to"))
        }

        val records = exportAuditLogUseCase.export(ExportAuditLogQuery(tenantId, from, to, type))
        val filename = "audit-${FILENAME_FMT.format(from)}-${FILENAME_FMT.format(to)}.ndjson"
        val ndjson = buildString { records.forEach { append(objectMapper.writeValueAsString(it)).append('\n') } }

        return ResponseEntity.ok()
            .contentType(NDJSON)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .body(ndjson)
    }
}
