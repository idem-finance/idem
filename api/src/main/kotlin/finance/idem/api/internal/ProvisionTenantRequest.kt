package finance.idem.api.internal

import finance.idem.core.tenant.TenantPlan
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class ProvisionTenantRequest(
    @field:NotBlank(message = "organizationName must not be blank")
    val organizationName: String = "",
    @field:NotBlank(message = "contactEmail must not be blank")
    @field:Email(message = "contactEmail must be a valid email address")
    val contactEmail: String = "",
    val plan: TenantPlan = TenantPlan.CLOUD,
)
