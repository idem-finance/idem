package finance.idem.infrastructure.persistence.outbox

import finance.idem.application.outbox.OutboxStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface WebhookOutboxJpaRepository : JpaRepository<WebhookOutboxDataModel, UUID> {
    fun findByTenantIdAndStatusInOrderByCreatedAtAsc(
        tenantId: UUID,
        statuses: List<OutboxStatus>,
    ): List<WebhookOutboxDataModel>

    /**
     * Cross-tenant — relies on `webhook_outbox` having NO FORCE RLS (V12), so
     * the table-owner role sees PENDING/FAILED rows across all tenants with
     * no `app.tenant_id` set.
     */
    @Query(
        value = """
            SELECT * FROM webhook_outbox
            WHERE status IN ('PENDING','FAILED') AND next_retry_at <= now()
            ORDER BY created_at ASC
            LIMIT :limit
        """,
        nativeQuery = true,
    )
    fun findDispatchable(
        @Param("limit") limit: Int,
    ): List<WebhookOutboxDataModel>

    @Modifying
    @Query(
        value = """
            UPDATE webhook_outbox
            SET status = 'DELIVERED', delivered_at = CAST(:deliveredAt AS timestamptz)
            WHERE id = CAST(:id AS uuid) AND tenant_id = CAST(:tenantId AS uuid)
        """,
        nativeQuery = true,
    )
    fun markDelivered(
        @Param("id") id: String,
        @Param("tenantId") tenantId: String,
        @Param("deliveredAt") deliveredAt: Instant,
    )

    @Modifying
    @Query(
        value = """
            UPDATE webhook_outbox
            SET status = 'FAILED', attempts = :attempts, next_retry_at = CAST(:nextRetryAt AS timestamptz), last_error = :lastError
            WHERE id = CAST(:id AS uuid) AND tenant_id = CAST(:tenantId AS uuid)
        """,
        nativeQuery = true,
    )
    fun markFailedForRetry(
        @Param("id") id: String,
        @Param("tenantId") tenantId: String,
        @Param("attempts") attempts: Int,
        @Param("nextRetryAt") nextRetryAt: Instant,
        @Param("lastError") lastError: String?,
    )

    @Modifying
    @Query(
        value = """
            UPDATE webhook_outbox
            SET status = 'DEAD', last_error = :lastError
            WHERE id = CAST(:id AS uuid) AND tenant_id = CAST(:tenantId AS uuid)
        """,
        nativeQuery = true,
    )
    fun markDead(
        @Param("id") id: String,
        @Param("tenantId") tenantId: String,
        @Param("lastError") lastError: String?,
    )
}
