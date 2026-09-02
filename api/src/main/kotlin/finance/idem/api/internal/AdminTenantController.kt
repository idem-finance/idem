package finance.idem.api.internal

import finance.idem.api.ledger.ErrorResponse
import finance.idem.application.port.AdminTokenAuthenticator
import finance.idem.application.port.AdminTokenLockoutGuard
import finance.idem.application.tenant.InvalidAdminToken
import finance.idem.application.tenant.ProvisionTenantCommand
import finance.idem.application.tenant.ProvisionTenantUseCase
import finance.idem.application.tenant.ProvisioningInProgress
import finance.idem.application.tenant.SuspendTenantUseCase
import finance.idem.application.tenant.TenantNotFound
import finance.idem.core.TenantId
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Validator
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Internal, non-customer-facing tenant provisioning API (#272). Authenticated by a
 * shared-secret admin token, not customer API keys — every path under `/internal`
 * is `permitAll()` at the Spring Security layer (see WebSecurityConfig), same as the
 * Alchemy/QuickNode webhook receivers; auth happens inside this controller (checked
 * FIRST, before body validation — see the ordering note on [provision]), not a filter.
 * Excluded from Swagger/OpenAPI docs via springdoc's `/internal` path exclusion. IP/VPC
 * restriction is an idem-infra deployment concern, not implemented here; brute-force
 * throttling on the token itself is handled by [AdminTokenLockoutService].
 *
 * `@Valid` is deliberately NOT used on the request bodies below — Bean Validation would
 * trigger during Spring MVC argument resolution, before this controller's body (and thus
 * the admin-token check) ever runs, so a malformed body from an UNauthenticated caller
 * would leak a 400 instead of a uniform 401. Validation is invoked manually, after the
 * token check.
 */
@RestController
@RequestMapping("/internal/admin/tenants")
class AdminTenantController(
    private val provisionTenantUseCase: ProvisionTenantUseCase,
    private val suspendTenantUseCase: SuspendTenantUseCase,
    private val adminTokenAuthenticator: AdminTokenAuthenticator,
    private val lockoutService: AdminTokenLockoutGuard,
    private val validator: Validator,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping
    fun provision(
        @RequestHeader(value = "X-Internal-Admin-Token", required = false) adminToken: String?,
        @RequestHeader(value = "Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody request: ProvisionTenantRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<Any> {
        authenticate(adminToken, httpRequest)?.let { return it }

        if (idempotencyKey.isNullOrBlank()) {
            return ResponseEntity.badRequest().body(ErrorResponse("VALIDATION_ERROR", "Idempotency-Key header is required"))
        }
        validationErrorOrNull(request)?.let { return it }

        val cmd =
            ProvisionTenantCommand(
                adminToken = adminToken,
                idempotencyKey = idempotencyKey,
                organizationName = request.organizationName,
                contactEmail = request.contactEmail,
            )

        return provisionTenantUseCase.execute(cmd).fold(
            onSuccess = { provisioned ->
                ResponseEntity.status(HttpStatus.CREATED).body(
                    ProvisionTenantResponse(
                        tenantId = provisioned.tenantId.value,
                        apiKey = provisioned.rawApiKey,
                        dashboardUrl = provisioned.dashboardUrl,
                    ),
                )
            },
            onFailure = { error -> error.toErrorResponse() },
        )
    }

    @PostMapping("/{id}/suspend")
    fun suspend(
        @RequestHeader(value = "X-Internal-Admin-Token", required = false) adminToken: String?,
        @PathVariable id: UUID,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<Any> {
        authenticate(adminToken, httpRequest)?.let { return it }

        return suspendTenantUseCase.execute(adminToken, TenantId(id)).fold(
            onSuccess = { suspendedAt -> ResponseEntity.ok(SuspendTenantResponse(id, suspendedAt)) },
            onFailure = { error -> error.toErrorResponse() },
        )
    }

    /** Returns a 429/401 response if the caller shouldn't proceed, `null` if the token check passed. */
    private fun authenticate(
        adminToken: String?,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<Any>? {
        val clientIp = httpRequest.remoteAddr
        if (lockoutService.isLockedOut(clientIp)) {
            return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ErrorResponse("TOO_MANY_ATTEMPTS", "Too many invalid admin-token attempts"))
        }
        if (!adminTokenAuthenticator.isValid(adminToken)) {
            lockoutService.recordFailure(clientIp)
            return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse("INVALID_ADMIN_TOKEN", "Missing or invalid admin token"))
        }
        return null
    }

    private fun validationErrorOrNull(request: ProvisionTenantRequest): ResponseEntity<Any>? {
        val violations = validator.validate(request)
        if (violations.isEmpty()) return null
        val message = violations.first().message
        return ResponseEntity.badRequest().body(ErrorResponse("VALIDATION_ERROR", message))
    }

    private fun Throwable.toErrorResponse(): ResponseEntity<Any> =
        when (this) {
            is InvalidAdminToken -> {
                ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse("INVALID_ADMIN_TOKEN", message ?: "Invalid admin token"))
            }

            is TenantNotFound -> {
                ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(ErrorResponse("TENANT_NOT_FOUND", message ?: "Tenant not found"))
            }

            is ProvisioningInProgress -> {
                ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ErrorResponse("PROVISIONING_IN_PROGRESS", message ?: "Provisioning already in progress"))
            }

            else -> {
                log.error("Unexpected error in tenant provisioning/suspend", this)
                ResponseEntity
                    .internalServerError()
                    .body(ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred"))
            }
        }
}
