package finance.idem.api.usage

import finance.idem.application.usage.UsageMeteringService
import finance.idem.core.TenantId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.YearMonth

@RestController
@RequestMapping("/api/v1/usage")
@Tag(name = "Usage", description = "Per-tenant usage metering for billing and self-serve visibility")
class UsageController(
    private val usageMeteringService: UsageMeteringService,
) {
    @GetMapping("/current-period")
    @PreAuthorize("hasAuthority('ADMIN')")
    @Operation(summary = "Current calendar-month usage per metric, alongside any configured limits")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Usage summary returned"),
        ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
        ApiResponse(responseCode = "403", description = "Requires ADMIN scope"),
    )
    fun currentPeriod(): ResponseEntity<Any> {
        val tenantId =
            SecurityContextHolder.getContext().authentication?.principal as? TenantId
                ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        val summary = usageMeteringService.getMonthlyUsage(tenantId, YearMonth.now())
        return ResponseEntity.ok(UsageSummaryResponse.from(summary))
    }
}
