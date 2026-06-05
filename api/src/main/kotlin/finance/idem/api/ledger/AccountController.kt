package finance.idem.api.ledger

import finance.idem.application.ledger.BalanceAccountNotFound
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
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
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
    @PreAuthorize("hasAuthority('ACCOUNTS_READ')")
    @Operation(summary = "Get account balance, optionally as of a past point in time")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Balance computed successfully"),
        ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
        ApiResponse(responseCode = "403", description = "API key lacks ACCOUNTS_READ scope"),
        ApiResponse(responseCode = "404", description = "Account not found for this tenant"),
    )
    fun getBalance(
        @Parameter(description = "Account UUID")
        @PathVariable accountId: UUID,
        @Parameter(description = "Return balance as of this ISO-8601 instant (omit for current balance)")
        @RequestParam(required = false) asOf: Instant?,
    ): ResponseEntity<Any> {
        val tenantId = SecurityContextHolder.getContext().authentication?.principal as? TenantId
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

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
                    is BalanceAccountNotFound ->
                        ResponseEntity.notFound().build()
                    else ->
                        ResponseEntity.internalServerError()
                            .body(ErrorResponse("INTERNAL_ERROR", error.message ?: "Unexpected error"))
                }
            },
        )
    }
}
