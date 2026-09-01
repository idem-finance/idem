package finance.idem.infrastructure.persistence.usage

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface UsageMetricHourlyJpaRepository : JpaRepository<UsageMetricHourlyDataModel, UUID> {
    fun findByTenantIdAndMetricTypeAndPeriodStartGreaterThanEqualAndPeriodStartLessThanOrderByPeriodStartAsc(
        tenantId: UUID,
        metricType: String,
        periodStartFrom: Instant,
        periodStartTo: Instant,
    ): List<UsageMetricHourlyDataModel>
}
