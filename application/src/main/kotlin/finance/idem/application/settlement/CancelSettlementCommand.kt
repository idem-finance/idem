package finance.idem.application.settlement

import finance.idem.core.TenantId
import java.util.UUID

data class CancelSettlementCommand(val id: UUID, val tenantId: TenantId)
