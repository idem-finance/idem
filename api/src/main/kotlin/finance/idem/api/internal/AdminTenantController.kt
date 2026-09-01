package finance.idem.api.internal

import finance.idem.api.ledger.ErrorResponse
import finance.idem.application.tenant.InvalidAdminToken
import finance.idem.application.tenant.ProvisionTenantCommand
import finance.idem.application.tenant.ProvisionTenantUseCase
import finance.idem.application.tenant.SuspendTenantUseCase
import finance.idem.application.tenant.TenantNotFound
import finance.idem.core.TenantId
import jakarta.validation.Valid
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
 * Alchemy/QuickNode webhook receivers; auth happens inside the use case. Excluded
 * from Swagger/OpenAPI docs via springdoc's `/internal` path exclusion. IP/VPC
 * restriction is an idem-infra deployment concern, not implemented here.
 */
@RestController
@RequestMapping("/internal/admin/tenants")
class AdminTenantController(
    private val provisionTenantUseCase: ProvisionTenantUseCase,
    private val suspendTenantUseCase: SuspendTenantUseCase,
) {
    @PostMapping
    fun provision(
        @RequestHeader(value = "X-Internal-Admin-Token", required = false) adminToken: String?,
        @Valid @RequestBody request: ProvisionTenantRequest,
    ): ResponseEntity<Any> {
        val cmd =
            ProvisionTenantCommand(
                adminToken = adminToken,
                organizationName = request.organizationName,
                contactEmail = request.contactEmail,
                plan = request.plan,
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
    ): ResponseEntity<Any> =
        suspendTenantUseCase.execute(adminToken, TenantId(id)).fold(
            onSuccess = { suspendedAt -> ResponseEntity.ok(SuspendTenantResponse(id, suspendedAt)) },
            onFailure = { error -> error.toErrorResponse() },
        )

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

            else -> {
                ResponseEntity
                    .internalServerError()
                    .body(ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred"))
            }
        }
}
