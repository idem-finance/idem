package finance.idem.infrastructure.persistence.outbox

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface WebhookOutboxJpaRepository : JpaRepository<WebhookOutboxDataModel, UUID> {

    fun findByTenantIdAndDispatchedFalseOrderByCreatedAtAsc(tenantId: UUID): List<WebhookOutboxDataModel>

    @Modifying
    @Query(
        value = """
            UPDATE webhook_outbox
            SET dispatched = true, dispatched_at = CAST(:now AS timestamptz)
            WHERE id = CAST(:id AS uuid) AND tenant_id = CAST(:tenantId AS uuid)
        """,
        nativeQuery = true,
    )
    fun markDispatched(
        @Param("id") id: String,
        @Param("tenantId") tenantId: String,
        @Param("now") now: Instant,
    )
}
