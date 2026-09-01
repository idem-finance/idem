package finance.idem.infrastructure.persistence.usage

import finance.idem.core.TenantId
import finance.idem.core.usage.MetricType
import finance.idem.core.usage.UsageMetric
import finance.idem.core.usage.UsageMetricRepository
import finance.idem.infrastructure.persistence.setRlsTenantId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class UsageMetricRepositoryAdapter(
    private val eventJpaRepository: UsageMetricEventJpaRepository,
    private val hourlyJpaRepository: UsageMetricHourlyJpaRepository,
    private val rollupStateJpaRepository: UsageMetricRollupStateJpaRepository,
    private val entityManager: EntityManager,
) : UsageMetricRepository {
    @Transactional
    override fun recordEvent(
        tenantId: TenantId,
        metricType: MetricType,
        amount: Long,
        occurredAt: Instant,
    ) {
        // usage_metrics is NO FORCE RLS (the rollup job needs cross-tenant reads as owner) —
        // setting app.tenant_id here is defense-in-depth, not a correctness requirement.
        entityManager.setRlsTenantId(tenantId)
        eventJpaRepository.save(
            UsageMetricEventDataModel.new(
                tenantId = tenantId.value,
                metricType = metricType.name,
                amount = amount,
                occurredAt = occurredAt,
            ),
        )
    }

    @Transactional(readOnly = true)
    override fun findHourlyBuckets(
        tenantId: TenantId,
        metricType: MetricType,
        from: Instant,
        to: Instant,
    ): List<UsageMetric> {
        // usage_metrics_hourly is FORCE RLS — app.tenant_id MUST be set or the owner role
        // sees zero rows, unlike the NO FORCE raw table above.
        entityManager.setRlsTenantId(tenantId)
        return hourlyJpaRepository
            .findByTenantIdAndMetricTypeAndPeriodStartGreaterThanEqualAndPeriodStartLessThanOrderByPeriodStartAsc(
                tenantId.value,
                metricType.name,
                from,
                to,
            ).map { it.toDomain() }
    }

    @Transactional(readOnly = true)
    override fun sumRawSince(
        tenantId: TenantId,
        metricType: MetricType,
        since: Instant,
    ): Long {
        entityManager.setRlsTenantId(tenantId)
        return eventJpaRepository.sumAmountSince(tenantId.value, metricType.name, since)
    }

    @Transactional
    override fun rollupHour(
        hourStart: Instant,
        hourEnd: Instant,
    ): Int =
        entityManager
            .createNativeQuery(
                """
                INSERT INTO usage_metrics_hourly (id, tenant_id, metric_type, value, period_start, period_end)
                SELECT gen_random_uuid(), tenant_id, metric_type, SUM(amount), :hourStart, :hourEnd
                FROM usage_metrics
                WHERE occurred_at >= :hourStart AND occurred_at < :hourEnd
                GROUP BY tenant_id, metric_type
                ON CONFLICT (tenant_id, metric_type, period_start) DO NOTHING
                """.trimIndent(),
            ).setParameter("hourStart", hourStart)
            .setParameter("hourEnd", hourEnd)
            .executeUpdate()

    @Transactional(readOnly = true)
    override fun currentWatermark(): Instant =
        rollupStateJpaRepository
            .findById(ROLLUP_STATE_ID)
            .orElseThrow { IllegalStateException("usage_metrics_rollup_state seed row is missing — V30 migration did not run") }
            .lastRolledUpHour

    @Transactional
    override fun advanceWatermark(newWatermark: Instant) {
        rollupStateJpaRepository.save(UsageMetricRollupStateDataModel(ROLLUP_STATE_ID, newWatermark))
    }

    companion object {
        private const val ROLLUP_STATE_ID: Short = 1
    }
}

private fun UsageMetricHourlyDataModel.toDomain() =
    UsageMetric(
        tenantId = TenantId(tenantId),
        metricType = MetricType.valueOf(metricType),
        value = value,
        periodStart = periodStart,
        periodEnd = periodEnd,
    )
