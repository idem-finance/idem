package finance.idem.api.policy

import finance.idem.api.ledger.ErrorResponse
import finance.idem.application.agentic.ManagePolicyRulesUseCase
import finance.idem.core.TenantId
import finance.idem.core.agentic.PolicyRuleId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
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
@RequestMapping("/api/v1/admin/policy-rules")
@Tag(name = "Policy Rules", description = "Agent policy rule management — configure what agent-originated transactions are permitted")
class PolicyRuleController(
    private val managePolicyRulesUseCase: ManagePolicyRulesUseCase,
) {

    @PostMapping
    @PreAuthorize("hasAuthority('ADMIN')")
    @Operation(summary = "Create a policy rule for this tenant")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Rule created"),
        ApiResponse(responseCode = "400", description = "Invalid rule type or missing required fields"),
        ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
        ApiResponse(responseCode = "403", description = "Requires ADMIN scope"),
    )
    fun create(@RequestBody request: CreatePolicyRuleRequest): ResponseEntity<Any> {
        val tenantId = tenantId()
        return try {
            val rule = request.toRule()
            val record = managePolicyRulesUseCase.create(tenantId, request.agentKeyPrefix, rule)
            ResponseEntity.status(HttpStatus.CREATED).body(record.toResponse())
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(ErrorResponse("INVALID_RULE", e.message ?: ""))
        }
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ADMIN')")
    @Operation(summary = "List all policy rules for this tenant")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Rule list"),
        ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
        ApiResponse(responseCode = "403", description = "Requires ADMIN scope"),
    )
    fun list(): ResponseEntity<List<PolicyRuleResponse>> =
        ResponseEntity.ok(managePolicyRulesUseCase.findAll(tenantId()).map { it.toResponse() })

    @DeleteMapping("/{ruleId}")
    @PreAuthorize("hasAuthority('ADMIN')")
    @Operation(summary = "Delete a policy rule")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "Rule deleted"),
        ApiResponse(responseCode = "404", description = "Rule not found for this tenant"),
        ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
        ApiResponse(responseCode = "403", description = "Requires ADMIN scope"),
    )
    fun delete(@PathVariable ruleId: UUID): ResponseEntity<Any> {
        val deleted = managePolicyRulesUseCase.delete(tenantId(), PolicyRuleId(ruleId))
        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse("POLICY_RULE_NOT_FOUND", "Rule not found for this tenant"))
        }
    }

    private fun tenantId(): TenantId =
        SecurityContextHolder.getContext().authentication.principal as TenantId
}
