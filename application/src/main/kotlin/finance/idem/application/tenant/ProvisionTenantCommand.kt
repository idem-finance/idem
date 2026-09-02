package finance.idem.application.tenant

data class ProvisionTenantCommand(
    val adminToken: String?,
    val idempotencyKey: String,
    val organizationName: String,
    val contactEmail: String,
)
