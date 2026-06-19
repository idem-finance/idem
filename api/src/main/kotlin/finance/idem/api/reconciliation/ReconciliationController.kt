package finance.idem.api.reconciliation

import finance.idem.api.ledger.ErrorResponse
import finance.idem.application.reconciliation.ReconcileBatchCommand
import finance.idem.application.reconciliation.ReconcileBatchUseCase
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/reconciliation")
@Tag(name = "Reconciliation", description = "Manual reconciliation triggers")
class ReconciliationController(
    private val reconcileBatchUseCase: ReconcileBatchUseCase,
) {

    @PostMapping("/batch")
    @PreAuthorize("hasAuthority('RECONCILIATION_WRITE')")
    @Operation(
        summary = "Trigger reconciliation for a batch of transactions",
        description = "Re-runs reconciliation for each listed transaction ID. Useful for transactions " +
            "that were not matched on initial commit, or after settlement expectations are updated.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Results for each transaction in the batch"),
        ApiResponse(responseCode = "400", description = "Empty or oversized batch"),
        ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
        ApiResponse(responseCode = "403", description = "Requires RECONCILIATION_WRITE scope"),
    )
    fun batch(@Valid @RequestBody request: ReconcileBatchRequest): ResponseEntity<Any> {
        val tenantId = SecurityContextHolder.getContext().authentication?.principal as? TenantId
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        val cmd = ReconcileBatchCommand(
            transactionIds = request.transactionIds.map { TransactionId(it) },
            tenantId = tenantId,
        )
        val results = reconcileBatchUseCase.execute(cmd).map { item ->
            ReconcileBatchItemResponse(
                transactionId = item.transactionId.value,
                outcome = item.outcome.name,
            )
        }
        return ResponseEntity.ok(results)
    }
}

data class ReconcileBatchRequest(
    @field:NotEmpty(message = "transactionIds must not be empty")
    @field:Size(max = 100, message = "batch size must not exceed 100")
    val transactionIds: List<UUID> = emptyList(),
)

data class ReconcileBatchItemResponse(
    val transactionId: UUID,
    val outcome: String,
)
