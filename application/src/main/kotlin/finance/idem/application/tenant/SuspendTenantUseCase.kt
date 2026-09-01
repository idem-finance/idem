package finance.idem.application.tenant

import finance.idem.core.TenantId
import java.time.Instant

interface SuspendTenantUseCase {
    fun execute(
        adminToken: String?,
        tenantId: TenantId,
    ): Result<Instant>
}
