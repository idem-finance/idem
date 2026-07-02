package finance.idem.application.settlement

import finance.idem.core.TenantId
import java.util.UUID

data class GetSettlementQuery(
    val id: UUID,
    val tenantId: TenantId,
)
