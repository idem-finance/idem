package finance.idem.infrastructure.persistence.usage

import finance.idem.core.TenantId
import finance.idem.core.usage.MetricType
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
        idempotencyKey: String?,
    ) {
        // usage_metrics carries a SELECT-only, idem_app-scoped service_cross_tenant_read
        // policy (V31) for the rollup job's cross-tenant read below — setting app.tenant_id
        // here is defense-in-depth for this INSERT, not a correctness requirement, since the
        // regular tenant_isolation policy already covers writes correctly either way.
        entityManager.setRlsTenantId(tenantId)
        if (idempotencyKey == null) {
            eventJpaRepository.save(
                UsageMetricEventDataModel.new(
                    tenantId = tenantId.value,
                    metricType = metricType.name,
                    amount = amount,
                    occurredAt = occurredAt,
                ),
            )
        } else {
            // JPA save() has no ON CONFLICT support and this table's PK is BIGSERIAL (not
            // natural), so an "insert or skip on dedup key" needs a native query.
            entityManager
                .createNativeQuery(
                    """
                    INSERT INTO usage_metrics (tenant_id, metric_type, amount, occurred_at, idempotency_key)
                    VALUES (:tenantId, :metricType, :amount, :occurredAt, :idempotencyKey)
                    ON CONFLICT (tenant_id, metric_type, idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING
                    """.trimIndent(),
                ).setParameter("tenantId", tenantId.value)
                .setParameter("metricType", metricType.name)
                .setParameter("amount", amount)
                .setParameter("occurredAt", occurredAt)
                .setParameter("idempotencyKey", idempotencyKey)
                .executeUpdate()
        }
    }

    @Transactional(readOnly = true)
    override fun hourlyBucketSums(
        tenantId: TenantId,
        from: Instant,
        to: Instant,
    ): Map<MetricType, Long> {
        // usage_metrics_hourly is FORCE RLS — app.tenant_id MUST be set or the owner role
        // sees zero rows, unlike the NO FORCE raw table above.
        entityManager.setRlsTenantId(tenantId)
        return hourlyJpaRepository.sumValueGroupedByMetricType(tenantId.value, from, to).toMetricTypeMap()
    }

    @Transactional(readOnly = true)
    override fun rawSumsBetween(
        tenantId: TenantId,
        from: Instant,
        to: Instant,
    ): Map<MetricType, Long> {
        entityManager.setRlsTenantId(tenantId)
        return eventJpaRepository.sumAmountGroupedByMetricType(tenantId.value, from, to).toMetricTypeMap()
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
            .orElseThrow { IllegalStateException("usage_metrics_rollup_state seed row is missing — V29 migration did not run") }
            .lastRolledUpHour

    @Transactional
    override fun advanceWatermark(newWatermark: Instant) {
        rollupStateJpaRepository.save(UsageMetricRollupStateDataModel(ROLLUP_STATE_ID, newWatermark))
    }

    companion object {
        private const val ROLLUP_STATE_ID: Short = 1
    }
}

private fun List<Array<Any>>.toMetricTypeMap(): Map<MetricType, Long> =
    associate { row -> MetricType.valueOf(row[0] as String) to (row[1] as Number).toLong() }
