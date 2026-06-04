package finance.idem.api.ledger

import finance.idem.application.ledger.IdempotencyConflict
import finance.idem.application.ledger.InvariantViolation
import finance.idem.application.ledger.PostTransactionError
import finance.idem.application.ledger.TransactionAccountNotFound
import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.core.LedgerInvariantViolation
import finance.idem.core.TenantId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transactions", description = "Double-entry transaction posting")
class TransactionController(
    private val postTransactionUseCase: PostTransactionUseCase,
) {

    @PostMapping
    @Operation(summary = "Post a balanced transaction")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Transaction committed"),
        ApiResponse(responseCode = "400", description = "Missing or invalid Idempotency-Key or malformed body"),
        ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
        ApiResponse(responseCode = "409", description = "Duplicate idempotency key for an in-progress transaction"),
        ApiResponse(responseCode = "422", description = "Account not found or double-entry invariant violated"),
    )
    fun postTransaction(
        @Parameter(description = "Client-generated idempotency key, max 255 chars", required = true)
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: PostTransactionRequest,
    ): ResponseEntity<Any> {
        if (idempotencyKey.isBlank() || idempotencyKey.length > 255) {
            return ResponseEntity.badRequest()
                .body(ErrorResponse("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must be non-blank and at most 255 characters"))
        }

        val tenantId = SecurityContextHolder.getContext().authentication.principal as TenantId

        val cmd = try {
            request.toCommand(tenantId, idempotencyKey)
        } catch (e: LedgerInvariantViolation) {
            return ResponseEntity.badRequest()
                .body(ErrorResponse("INVALID_REQUEST", e.message ?: "Invalid monetary entry"))
        } catch (e: IllegalArgumentException) {
            return ResponseEntity.badRequest()
                .body(ErrorResponse("INVALID_REQUEST", e.message ?: "Invalid request"))
        }

        return postTransactionUseCase.execute(cmd).fold(
            onSuccess = { txId ->
                ResponseEntity.status(HttpStatus.CREATED)
                    .body(PostTransactionResponse(txId.value))
            },
            onFailure = { error ->
                when (error) {
                    is IdempotencyConflict ->
                        ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(ErrorResponse("IDEMPOTENCY_CONFLICT", error.message ?: ""))
                    is TransactionAccountNotFound ->
                        ResponseEntity.unprocessableEntity()
                            .body(ErrorResponse("ACCOUNT_NOT_FOUND", error.message ?: ""))
                    is InvariantViolation ->
                        ResponseEntity.unprocessableEntity()
                            .body(ErrorResponse("INVARIANT_VIOLATION", error.message ?: ""))
                    else ->
                        ResponseEntity.internalServerError()
                            .body(ErrorResponse("INTERNAL_ERROR", error.message ?: "Unexpected error"))
                }
            },
        )
    }
}
