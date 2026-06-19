package finance.idem.api.security

import finance.idem.api.ledger.ErrorResponse
import finance.idem.application.security.GenerateApiKeyCommand
import finance.idem.application.security.GenerateApiKeyUseCase
import finance.idem.application.security.InsufficientCallerScope
import finance.idem.application.security.ListApiKeysUseCase
import finance.idem.application.security.RevokeApiKeyUseCase
import finance.idem.core.TenantId
import finance.idem.core.security.ApiKeyId
import finance.idem.core.security.ApiScope
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
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
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/api-keys")
@Tag(name = "API Keys", description = "Tenant API key management")
class ApiKeyController(
    private val generateUseCase: GenerateApiKeyUseCase,
    private val listUseCase: ListApiKeysUseCase,
    private val revokeUseCase: RevokeApiKeyUseCase,
) {

    @PostMapping
    @PreAuthorize("hasAuthority('ADMIN')")
    @Operation(summary = "Create a new API key with the given scopes")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Key created — raw key shown once"),
        ApiResponse(responseCode = "400", description = "Validation error or scope not allowed for caller"),
        ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
        ApiResponse(responseCode = "403", description = "Requires ADMIN scope"),
    )
    fun create(@Valid @RequestBody request: CreateApiKeyRequest): ResponseEntity<Any> {
        val auth = SecurityContextHolder.getContext().authentication
        val tenantId = auth?.principal as? TenantId
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        val callerScopes = auth.authorities
            .mapNotNullTo(mutableSetOf()) { runCatching { ApiScope.valueOf(it.authority) }.getOrNull() }

        val cmd = GenerateApiKeyCommand(
            tenantId = tenantId,
            requestedScopes = request.scopes,
            callerScopes = callerScopes,
        )

        return generateUseCase.execute(cmd).fold(
            onSuccess = { generated ->
                ResponseEntity.status(HttpStatus.CREATED).body(
                    CreateApiKeyResponse(
                        id = generated.apiKey.id.value,
                        rawKey = generated.rawKey,
                        prefix = generated.apiKey.prefix,
                        scopes = generated.apiKey.scopes.map { it.name },
                        createdAt = generated.apiKey.createdAt,
                    )
                )
            },
            onFailure = { error ->
                when (error) {
                    is InsufficientCallerScope ->
                        ResponseEntity.badRequest()
                            .body(ErrorResponse("INSUFFICIENT_CALLER_SCOPE", error.message ?: ""))
                    else ->
                        ResponseEntity.internalServerError()
                            .body(ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred"))
                }
            },
        )
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ADMIN')")
    @Operation(summary = "List all API keys for this tenant")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Key list (hashes omitted)"),
        ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
        ApiResponse(responseCode = "403", description = "Requires ADMIN scope"),
    )
    fun list(): ResponseEntity<Any> {
        val tenantId = SecurityContextHolder.getContext().authentication?.principal as? TenantId
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        val keys = listUseCase.execute(tenantId).map { ApiKeyResponse.from(it) }
        return ResponseEntity.ok(keys)
    }

    @DeleteMapping("/{keyId}")
    @PreAuthorize("hasAuthority('ADMIN')")
    @Operation(summary = "Revoke an API key")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Key revoked"),
        ApiResponse(responseCode = "404", description = "Key not found for this tenant"),
        ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
        ApiResponse(responseCode = "403", description = "Requires ADMIN scope"),
    )
    fun revoke(@PathVariable keyId: UUID): ResponseEntity<Any> {
        val tenantId = SecurityContextHolder.getContext().authentication?.principal as? TenantId
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        val found = revokeUseCase.execute(ApiKeyId(keyId), tenantId)
        return if (found) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse("API_KEY_NOT_FOUND", "Key not found for this tenant"))
        }
    }
}
