package finance.idem.api.settlement

import finance.idem.api.ledger.ErrorResponse
import finance.idem.application.settlement.CancelSettlementCommand
import finance.idem.application.settlement.CancelSettlementUseCase
import finance.idem.application.settlement.GetSettlementQuery
import finance.idem.application.settlement.GetSettlementUseCase
import finance.idem.application.settlement.ListSettlementsQuery
import finance.idem.application.settlement.ListSettlementsUseCase
import finance.idem.application.settlement.RegisterSettlementCommand
import finance.idem.application.settlement.RegisterSettlementUseCase
import finance.idem.application.settlement.SettlementAlreadyTerminal
import finance.idem.application.settlement.SettlementNotFound
import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.ledger.EntryStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/settlements")
@Tag(name = "Settlements", description = "Settlement expectation management")
class SettlementController(
    private val registerSettlementUseCase: RegisterSettlementUseCase,
    private val getSettlementUseCase: GetSettlementUseCase,
    private val listSettlementsUseCase: ListSettlementsUseCase,
    private val cancelSettlementUseCase: CancelSettlementUseCase,
    @Value("\${idem.reconciliation.matching-window-hours:24}") private val defaultMatchWindowHours: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/pending")
    @PreAuthorize("hasAuthority('TRANSACTIONS_WRITE')")
    @Operation(summary = "Register a pending settlement expectation")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Settlement registered — status PENDING, watching for on-chain transfer"),
        ApiResponse(responseCode = "400", description = "Malformed request body or invalid token/chainId values"),
        ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
        ApiResponse(responseCode = "403", description = "API key does not have the TRANSACTIONS_WRITE scope"),
        ApiResponse(responseCode = "422", description = "Account not found for this tenant"),
    )
    fun register(@Valid @RequestBody request: RegisterSettlementRequest): ResponseEntity<Any> {
        val tenantId = tenantId() ?: return unauthorized()

        val amount = try {
            MonetaryAmount.of(request.expectedAmount)
        } catch (e: Exception) {
            return ResponseEntity.badRequest()
                .body(ErrorResponse("INVALID_AMOUNT", "expectedAmount is not a valid decimal: ${request.expectedAmount}"))
        }

        val token = try {
            StablecoinToken.valueOf(request.expectedToken.uppercase())
        } catch (e: IllegalArgumentException) {
            return ResponseEntity.badRequest()
                .body(ErrorResponse("INVALID_TOKEN", "expectedToken must be one of: ${StablecoinToken.entries.joinToString()}"))
        }

        val chainId = try {
            ChainId.valueOf(request.expectedChainId.uppercase())
        } catch (e: IllegalArgumentException) {
            return ResponseEntity.badRequest()
                .body(ErrorResponse("INVALID_CHAIN_ID", "expectedChainId must be one of: ${ChainId.entries.joinToString()}"))
        }

        val cmd = RegisterSettlementCommand(
            tenantId = tenantId,
            accountId = AccountId(request.accountId),
            amount = amount,
            token = token,
            chainId = chainId,
            walletAddress = request.expectedWalletAddress,
            expectedFromAddress = request.expectedFromAddress,
            createdBy = SecurityContextHolder.getContext().authentication?.name ?: "unknown",
        )

        val matchWindowHours = request.matchWindowHours ?: defaultMatchWindowHours

        return registerSettlementUseCase.execute(cmd).fold(
            onSuccess = { settlement ->
                ResponseEntity.status(HttpStatus.CREATED)
                    .body(SettlementResponse.from(settlement, matchWindowHours))
            },
            onFailure = { error ->
                when (error) {
                    is finance.idem.application.settlement.AccountNotFoundForSettlement ->
                        ResponseEntity.unprocessableEntity()
                            .body(ErrorResponse("ACCOUNT_NOT_FOUND", error.message ?: "Account not found"))
                    else -> {
                        log.error("Unexpected error registering settlement", error)
                        ResponseEntity.internalServerError()
                            .body(ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred"))
                    }
                }
            },
        )
    }

    @GetMapping("/pending")
    @PreAuthorize("hasAuthority('TRANSACTIONS_READ')")
    @Operation(summary = "List settlements for this tenant with optional filters")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Paginated list of settlements"),
        ApiResponse(responseCode = "400", description = "Invalid status value, invalid range, or malformed cursor"),
        ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
        ApiResponse(responseCode = "403", description = "API key does not have the TRANSACTIONS_READ scope"),
    )
    fun list(
        @Parameter(description = "Filter by status (PENDING, SETTLED, UNMATCHED, CANCELLED)")
        @RequestParam(required = false) status: String?,
        @Parameter(description = "Inclusive lower bound on createdAt")
        @RequestParam(required = false) from: Instant?,
        @Parameter(description = "Inclusive upper bound on createdAt")
        @RequestParam(required = false) to: Instant?,
        @Parameter(description = "Max results per page, 1-200")
        @RequestParam(defaultValue = "50") limit: Int,
        @Parameter(description = "Opaque pagination cursor from a previous page's nextCursor")
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<Any> {
        val tenantId = tenantId() ?: return unauthorized()

        if (limit < 1 || limit > 200) {
            return ResponseEntity.badRequest()
                .body(ErrorResponse("INVALID_LIMIT", "limit must be between 1 and 200"))
        }

        if (from != null && to != null && from.isAfter(to)) {
            return ResponseEntity.badRequest()
                .body(ErrorResponse("INVALID_RANGE", "from must not be after to"))
        }

        val entryStatus = status?.let {
            try {
                EntryStatus.valueOf(it.uppercase())
            } catch (e: IllegalArgumentException) {
                return ResponseEntity.badRequest()
                    .body(ErrorResponse("INVALID_STATUS", "status must be one of: ${EntryStatus.entries.joinToString()}"))
            }
        }

        val query = ListSettlementsQuery(
            tenantId = tenantId,
            status = entryStatus,
            from = from,
            to = to,
            limit = limit,
            cursor = cursor,
        )

        return listSettlementsUseCase.execute(query).fold(
            onSuccess = { page ->
                val response = SettlementListResponse(
                    settlements = page.settlements.map { SettlementResponse.from(it, defaultMatchWindowHours) },
                    nextCursor = page.nextCursor,
                )
                ResponseEntity.ok(response)
            },
            onFailure = { error ->
                when (error) {
                    is finance.idem.application.ledger.InvalidCursor ->
                        ResponseEntity.badRequest()
                            .body(ErrorResponse("INVALID_CURSOR", error.message ?: "Invalid cursor"))
                    else -> {
                        log.error("Unexpected error listing settlements", error)
                        ResponseEntity.internalServerError()
                            .body(ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred"))
                    }
                }
            },
        )
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('TRANSACTIONS_READ')")
    @Operation(summary = "Get a settlement by ID")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Settlement found"),
        ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
        ApiResponse(responseCode = "403", description = "API key does not have the TRANSACTIONS_READ scope"),
        ApiResponse(responseCode = "404", description = "Settlement not found for this tenant"),
    )
    fun getById(
        @Parameter(description = "Settlement UUID") @PathVariable id: UUID,
    ): ResponseEntity<Any> {
        val tenantId = tenantId() ?: return unauthorized()

        return getSettlementUseCase.execute(GetSettlementQuery(id, tenantId)).fold(
            onSuccess = { settlement ->
                ResponseEntity.ok(SettlementResponse.from(settlement, defaultMatchWindowHours))
            },
            onFailure = { error ->
                when (error) {
                    is SettlementNotFound -> ResponseEntity.notFound().build()
                    else -> {
                        log.error("Unexpected error fetching settlement $id", error)
                        ResponseEntity.internalServerError()
                            .body(ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred"))
                    }
                }
            },
        )
    }

    @DeleteMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('TRANSACTIONS_WRITE')")
    @Operation(summary = "Cancel a PENDING settlement")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Settlement cancelled"),
        ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
        ApiResponse(responseCode = "403", description = "API key does not have the TRANSACTIONS_WRITE scope"),
        ApiResponse(responseCode = "404", description = "Settlement not found for this tenant"),
        ApiResponse(responseCode = "409", description = "Settlement is already in a terminal status and cannot be cancelled"),
    )
    fun cancel(
        @Parameter(description = "Settlement UUID") @PathVariable id: UUID,
    ): ResponseEntity<Any> {
        val tenantId = tenantId() ?: return unauthorized()

        return cancelSettlementUseCase.execute(CancelSettlementCommand(id, tenantId)).fold(
            onSuccess = { settlement ->
                ResponseEntity.ok(SettlementResponse.from(settlement, defaultMatchWindowHours))
            },
            onFailure = { error ->
                when (error) {
                    is SettlementNotFound -> ResponseEntity.notFound().build()
                    is SettlementAlreadyTerminal ->
                        ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(ErrorResponse("SETTLEMENT_ALREADY_TERMINAL", error.message ?: ""))
                    else -> {
                        log.error("Unexpected error cancelling settlement $id", error)
                        ResponseEntity.internalServerError()
                            .body(ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred"))
                    }
                }
            },
        )
    }

    private fun tenantId(): TenantId? =
        SecurityContextHolder.getContext().authentication?.principal as? TenantId

    private fun unauthorized(): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
}
