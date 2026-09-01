package finance.idem.application.tenant

interface ProvisionTenantUseCase {
    fun execute(cmd: ProvisionTenantCommand): Result<ProvisionedTenant>
}
