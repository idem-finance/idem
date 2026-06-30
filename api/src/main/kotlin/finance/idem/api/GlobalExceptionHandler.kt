package finance.idem.api

import finance.idem.api.ledger.ErrorResponse
import finance.idem.application.ledger.BalanceAccountNotFound
import finance.idem.application.ledger.EntriesAccountNotFound
import finance.idem.application.ledger.IdempotencyConflict
import finance.idem.application.ledger.InvalidCursor
import finance.idem.application.ledger.InvalidStatementRange
import finance.idem.application.ledger.InvariantViolation
import finance.idem.application.ledger.StatementAccountNotFound
import finance.idem.application.ledger.TransactionAccountNotFound
import finance.idem.application.settlement.AccountNotFoundForSettlement
import finance.idem.application.settlement.SettlementAlreadyTerminal
import finance.idem.application.settlement.SettlementNotFound
import finance.idem.core.LedgerInvariantViolation
import finance.idem.core.agentic.PolicyViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Converts domain and Spring MVC exceptions to JSON [ErrorResponse] shapes.
 *
 * Extends [ResponseEntityExceptionHandler] so that Spring MVC exceptions
 * (e.g. [org.springframework.web.bind.MissingRequestHeaderException]) receive
 * their standard 4xx responses rather than falling through to the generic
 * [handleUnexpected] fallback.
 */
@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(javaClass)

    // Override the parent's MethodArgumentNotValidException handler to return our error shape.
    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val message = ex.bindingResult.fieldErrors
            .firstOrNull()?.defaultMessage ?: "Request validation failed"
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse("VALIDATION_ERROR", message))
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ErrorResponse("insufficient_scope", "API key does not have the required scope"))

    @ExceptionHandler(PolicyViolationException::class)
    fun handlePolicyViolation(ex: PolicyViolationException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ErrorResponse("POLICY_VIOLATION", ex.violations.joinToString("; ") { it.message }))

    @ExceptionHandler(LedgerInvariantViolation::class)
    fun handleLedgerInvariantViolation(ex: LedgerInvariantViolation): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(ErrorResponse("INVARIANT_VIOLATION", ex.message ?: "Ledger invariant violated"))

    @ExceptionHandler(InvariantViolation::class)
    fun handleInvariantViolation(ex: InvariantViolation): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(ErrorResponse("INVARIANT_VIOLATION", ex.detail))

    @ExceptionHandler(IdempotencyConflict::class)
    fun handleIdempotencyConflict(ex: IdempotencyConflict): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("IDEMPOTENCY_CONFLICT", ex.message ?: ""))

    @ExceptionHandler(
        TransactionAccountNotFound::class,
        BalanceAccountNotFound::class,
        EntriesAccountNotFound::class,
        StatementAccountNotFound::class,
    )
    fun handleAccountNotFound(ex: Exception): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("ACCOUNT_NOT_FOUND", ex.message ?: "Account not found"))

    @ExceptionHandler(InvalidStatementRange::class)
    fun handleInvalidStatementRange(ex: InvalidStatementRange): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(ErrorResponse("INVALID_RANGE", ex.message ?: "Invalid statement range"))

    @ExceptionHandler(InvalidCursor::class)
    fun handleInvalidCursor(ex: InvalidCursor): ResponseEntity<ErrorResponse> =
        ResponseEntity.badRequest()
            .body(ErrorResponse("INVALID_CURSOR", ex.message ?: "Invalid cursor"))

    @ExceptionHandler(SettlementNotFound::class)
    fun handleSettlementNotFound(ex: SettlementNotFound): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("SETTLEMENT_NOT_FOUND", ex.message ?: "Settlement not found"))

    @ExceptionHandler(SettlementAlreadyTerminal::class)
    fun handleSettlementAlreadyTerminal(ex: SettlementAlreadyTerminal): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("SETTLEMENT_ALREADY_TERMINAL", ex.message ?: "Settlement is already terminal"))

    @ExceptionHandler(AccountNotFoundForSettlement::class)
    fun handleAccountNotFoundForSettlement(ex: AccountNotFoundForSettlement): ResponseEntity<ErrorResponse> =
        ResponseEntity.unprocessableEntity()
            .body(ErrorResponse("ACCOUNT_NOT_FOUND", ex.message ?: "Account not found"))

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unhandled exception", ex)
        return ResponseEntity.internalServerError()
            .body(ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred"))
    }
}
