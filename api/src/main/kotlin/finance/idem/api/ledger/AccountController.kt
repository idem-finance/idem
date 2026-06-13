package finance.idem.api.ledger

import finance.idem.application.ledger.BalanceAccountNotFound
import finance.idem.application.ledger.EntriesAccountNotFound
import finance.idem.application.ledger.GenerateStatementQuery
import finance.idem.application.ledger.GenerateStatementUseCase
import finance.idem.application.ledger.InvalidCursor
import finance.idem.application.ledger.GetEntriesQuery
import finance.idem.application.ledger.GetEntriesUseCase
import finance.idem.application.ledger.GetBalanceQuery
import finance.idem.application.ledger.GetBalanceUseCase
import finance.idem.application.ledger.StatementAccountNotFound
import finance.idem.core.AccountId
import finance.idem.core.TenantId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts", description = "Account balance queries")
class AccountController(
    private val getBalanceUseCase: GetBalanceUseCase,
    private val getEntriesUseCase: GetEntriesUseCase,
    private val generateStatementUseCase: GenerateStatementUseCase,
) {

    @GetMapping("/{accountId}/balance")
    @Operation(summary = "Get account balance, optionally as of a past point in time")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Balance computed successfully"),
        ApiResponse(responseCode = "400", description = "Missing or invalid X-Tenant-Id, or invalid accountId format"),
        ApiResponse(responseCode = "404", description = "Account not found for this tenant"),
    )
    fun getBalance(
        @Parameter(description = "Tenant UUID", required = true)
        @RequestHeader("X-Tenant-Id") tenantIdStr: String,
        @Parameter(description = "Account UUID")
        @PathVariable accountId: UUID,
        @Parameter(description = "Return balance as of this ISO-8601 instant (omit for current balance)")
        @RequestParam(required = false) asOf: Instant?,
    ): ResponseEntity<Any> {
        val tenantId = try {
            TenantId(UUID.fromString(tenantIdStr))
        } catch (_: IllegalArgumentException) {
            return ResponseEntity.badRequest()
                .body(ErrorResponse("INVALID_TENANT_ID", "X-Tenant-Id must be a valid UUID"))
        }

        val query = GetBalanceQuery(
            accountId = AccountId(accountId),
            tenantId = tenantId,
            asOf = asOf,
        )

        return getBalanceUseCase.execute(query).fold(
            onSuccess = { balance ->
                ResponseEntity.ok(BalanceResponse.from(balance))
            },
            onFailure = { error ->
                when (error) {
                    is BalanceAccountNotFound ->
                        ResponseEntity.notFound().build()
                    else ->
                        ResponseEntity.internalServerError()
                            .body(ErrorResponse("INTERNAL_ERROR", error.message ?: "Unexpected error"))
                }
            },
        )
    }

    @GetMapping("/{accountId}/entries")
    @Operation(summary = "Get a paginated, reverse-chronological timeline of journal entries for an account")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Page of journal entries returned successfully"),
        ApiResponse(responseCode = "400", description = "Missing or invalid X-Tenant-Id, invalid accountId/limit/range, or invalid cursor"),
        ApiResponse(responseCode = "404", description = "Account not found for this tenant"),
    )
    fun listEntries(
        @Parameter(description = "Tenant UUID", required = true)
        @RequestHeader("X-Tenant-Id") tenantIdStr: String,
        @Parameter(description = "Account UUID")
        @PathVariable accountId: UUID,
        @Parameter(description = "Inclusive lower bound on createdAt; must not be after 'to' if both are present")
        @RequestParam(required = false) from: Instant?,
        @Parameter(description = "Inclusive upper bound on createdAt; must not be before 'from' if both are present")
        @RequestParam(required = false) to: Instant?,
        @Parameter(description = "Max entries per page, 1-200")
        @RequestParam(defaultValue = "50") limit: Int,
        @Parameter(description = "Opaque pagination cursor from a previous page's nextCursor")
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<Any> {
        val tenantId = try {
            TenantId(UUID.fromString(tenantIdStr))
        } catch (_: IllegalArgumentException) {
            return ResponseEntity.badRequest()
                .body(ErrorResponse("INVALID_TENANT_ID", "X-Tenant-Id must be a valid UUID"))
        }

        if (limit < 1 || limit > 200) {
            return ResponseEntity.badRequest()
                .body(ErrorResponse("INVALID_LIMIT", "limit must be between 1 and 200"))
        }

        if (from != null && to != null && from.isAfter(to)) {
            return ResponseEntity.badRequest()
                .body(ErrorResponse("INVALID_RANGE", "from must not be after to"))
        }

        val query = GetEntriesQuery(
            accountId = AccountId(accountId),
            tenantId = tenantId,
            from = from,
            to = to,
            limit = limit,
            cursor = cursor,
        )

        return getEntriesUseCase.execute(query).fold(
            onSuccess = { page ->
                ResponseEntity.ok(EntryTimelineResponse.from(page))
            },
            onFailure = { error ->
                when (error) {
                    is EntriesAccountNotFound ->
                        ResponseEntity.notFound().build()
                    is InvalidCursor ->
                        ResponseEntity.badRequest()
                            .body(ErrorResponse("INVALID_CURSOR", error.message ?: "Invalid cursor"))
                    else ->
                        ResponseEntity.internalServerError()
                            .body(ErrorResponse("INTERNAL_ERROR", error.message ?: "Unexpected error"))
                }
            },
        )
    }

    @GetMapping("/{accountId}/statement")
    @Operation(summary = "Generate an account statement for a period, with opening/closing balances and movements")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Statement generated successfully"),
        ApiResponse(responseCode = "400", description = "Missing or invalid X-Tenant-Id, missing from/to, invalid accountId format, or from after to"),
        ApiResponse(responseCode = "404", description = "Account not found for this tenant"),
    )
    fun getStatement(
        @Parameter(description = "Tenant UUID", required = true)
        @RequestHeader("X-Tenant-Id") tenantIdStr: String,
        @Parameter(description = "Account UUID")
        @PathVariable accountId: UUID,
        @Parameter(description = "Inclusive lower bound on occurredAt for the statement period", required = true)
        @RequestParam(required = false) from: Instant?,
        @Parameter(description = "Inclusive upper bound on occurredAt for the statement period", required = true)
        @RequestParam(required = false) to: Instant?,
    ): ResponseEntity<Any> {
        val tenantId = try {
            TenantId(UUID.fromString(tenantIdStr))
        } catch (_: IllegalArgumentException) {
            return ResponseEntity.badRequest()
                .body(ErrorResponse("INVALID_TENANT_ID", "X-Tenant-Id must be a valid UUID"))
        }

        if (from == null || to == null) {
            return ResponseEntity.badRequest()
                .body(ErrorResponse("MISSING_PARAMETER", "from and to are required"))
        }

        if (from.isAfter(to)) {
            return ResponseEntity.badRequest()
                .body(ErrorResponse("INVALID_RANGE", "from must not be after to"))
        }

        val query = GenerateStatementQuery(
            accountId = AccountId(accountId),
            tenantId = tenantId,
            from = from,
            to = to,
        )

        return generateStatementUseCase.execute(query).fold(
            onSuccess = { statement ->
                ResponseEntity.ok(StatementResponse.from(statement))
            },
            onFailure = { error ->
                when (error) {
                    is StatementAccountNotFound ->
                        ResponseEntity.notFound().build()
                    else ->
                        ResponseEntity.internalServerError()
                            .body(ErrorResponse("INTERNAL_ERROR", error.message ?: "Unexpected error"))
                }
            },
        )
    }
}
