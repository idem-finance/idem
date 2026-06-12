package finance.idem.application.outbox

import finance.idem.core.TenantId
import java.util.UUID

data class WebhookOutboxDispatch(
    val id: UUID,
    val tenantId: TenantId,
    val eventType: String,
    val payload: String,
    val attempts: Int,
)
