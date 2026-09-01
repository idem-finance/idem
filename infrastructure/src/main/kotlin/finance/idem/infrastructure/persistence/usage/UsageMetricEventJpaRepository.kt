package finance.idem.infrastructure.persistence.usage

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface UsageMetricEventJpaRepository : JpaRepository<UsageMetricEventDataModel, Long> {
    @Query(
        "SELECT e.metricType, COALESCE(SUM(e.amount), 0) FROM UsageMetricEventDataModel e " +
            "WHERE e.tenantId = :tenantId AND e.occurredAt >= :from AND e.occurredAt < :to " +
            "GROUP BY e.metricType",
    )
    fun sumAmountGroupedByMetricType(
        @Param("tenantId") tenantId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<Array<Any>>
}
