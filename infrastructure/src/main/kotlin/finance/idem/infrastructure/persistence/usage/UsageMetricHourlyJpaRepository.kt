package finance.idem.infrastructure.persistence.usage

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface UsageMetricHourlyJpaRepository : JpaRepository<UsageMetricHourlyDataModel, UUID> {
    @Query(
        "SELECT h.metricType, COALESCE(SUM(h.value), 0) FROM UsageMetricHourlyDataModel h " +
            "WHERE h.tenantId = :tenantId AND h.periodStart >= :from AND h.periodStart < :to " +
            "GROUP BY h.metricType",
    )
    fun sumValueGroupedByMetricType(
        @Param("tenantId") tenantId: UUID,
        @Param("from") from: Instant,
        @Param("to") to: Instant,
    ): List<Array<Any>>
}
