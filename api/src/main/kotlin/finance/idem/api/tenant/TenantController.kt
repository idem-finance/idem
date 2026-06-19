package finance.idem.api.tenant

import finance.idem.api.ledger.ErrorResponse
import finance.idem.application.tenant.GetWebhookConfigUseCase
import finance.idem.application.tenant.UpdateWebhookConfigUseCase
import finance.idem.core.TenantId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/tenant")
@Tag(name = "Tenant", description = "Tenant configuration")
class TenantController(
    private val updateWebhookConfigUseCase: UpdateWebhookConfigUseCase,
    private val getWebhookConfigUseCase: GetWebhookConfigUseCase,
) {

    @PutMapping("/webhook")
    @PreAuthorize("hasAuthority('WEBHOOK_MANAGE')")
    @Operation(summary = "Register or update the tenant webhook URL")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Webhook configured — secret shown once"),
        ApiResponse(responseCode = "400", description = "Invalid or blocked webhook URL"),
        ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
        ApiResponse(responseCode = "403", description = "Requires WEBHOOK_MANAGE scope"),
    )
    fun updateWebhook(@Valid @RequestBody request: UpdateWebhookConfigRequest): ResponseEntity<Any> {
        val tenantId = SecurityContextHolder.getContext().authentication?.principal as? TenantId
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        return updateWebhookConfigUseCase.execute(tenantId, request.webhookUrl).fold(
            onSuccess = { config ->
                ResponseEntity.ok(
                    WebhookConfigCreatedResponse(
                        webhookUrl = config.webhookUrl,
                        webhookSecret = config.webhookSecret,
                    )
                )
            },
            onFailure = { error ->
                ResponseEntity.badRequest()
                    .body(ErrorResponse("INVALID_WEBHOOK_URL", error.message ?: "Invalid webhook URL"))
            },
        )
    }

    @GetMapping("/webhook")
    @PreAuthorize("hasAuthority('WEBHOOK_MANAGE')")
    @Operation(summary = "Retrieve the tenant webhook configuration (secret masked)")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Current webhook config"),
        ApiResponse(responseCode = "404", description = "Webhook not configured"),
        ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
        ApiResponse(responseCode = "403", description = "Requires WEBHOOK_MANAGE scope"),
    )
    fun getWebhook(): ResponseEntity<Any> {
        val tenantId = SecurityContextHolder.getContext().authentication?.principal as? TenantId
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        val config = getWebhookConfigUseCase.execute(tenantId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse("WEBHOOK_NOT_CONFIGURED", "No webhook URL configured for this tenant"))

        return ResponseEntity.ok(
            WebhookConfigResponse(
                webhookUrl = config.webhookUrl,
                secretPrefix = config.webhookSecret.take(8) + "...",
            )
        )
    }
}
