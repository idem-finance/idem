package finance.idem.application.port

import finance.idem.application.outbox.WebhookOutboxDispatch
import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.core.TenantId
import java.time.Instant
import java.util.UUID

interface WebhookOutboxRepository {
    fun save(entry: WebhookOutboxEntry)

    /** Cross-tenant — relies on `webhook_outbox` having NO FORCE RLS (V12). */
    fun findDispatchable(limit: Int): List<WebhookOutboxDispatch>

    fun markDelivered(id: UUID, tenantId: TenantId)

    fun markFailedForRetry(id: UUID, tenantId: TenantId, attempts: Int, nextRetryAt: Instant, lastError: String?)

    fun markDead(id: UUID, tenantId: TenantId, lastError: String?)
}
