package finance.idem.api.ledger

import finance.idem.application.ledger.QueryBalanceError
import finance.idem.application.ledger.QueryBalanceQuery
import finance.idem.application.ledger.QueryBalanceUseCase
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
    private val queryBalanceUseCase: QueryBalanceUseCase,
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

        val query = QueryBalanceQuery(
            accountId = AccountId(accountId),
            tenantId = tenantId,
            asOf = asOf,
        )

        return queryBalanceUseCase.execute(query).fold(
            onSuccess = { balance ->
                ResponseEntity.ok(BalanceResponse.from(balance))
            },
            onFailure = { error ->
                when (error) {
                    is QueryBalanceError.AccountNotFound ->
                        ResponseEntity.notFound().build()
                    else ->
                        ResponseEntity.internalServerError()
                            .body(ErrorResponse("INTERNAL_ERROR", error.message ?: "Unexpected error"))
                }
            },
        )
    }
}
