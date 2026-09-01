package finance.idem.infrastructure.persistence.usage

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface UsageMetricEventJpaRepository : JpaRepository<UsageMetricEventDataModel, Long> {
    @Query(
        "SELECT COALESCE(SUM(e.amount), 0) FROM UsageMetricEventDataModel e " +
            "WHERE e.tenantId = :tenantId AND e.metricType = :metricType AND e.occurredAt >= :since",
    )
    fun sumAmountSince(
        @Param("tenantId") tenantId: UUID,
        @Param("metricType") metricType: String,
        @Param("since") since: Instant,
    ): Long
}
