package finance.idem.application.usage

import finance.idem.core.TenantId
import finance.idem.core.usage.MetricType
import finance.idem.core.usage.UsageSummary
import java.time.YearMonth

interface UsageMeteringService {
    /**
     * Records a usage event for billing/self-serve visibility.
     *
     * Participates in the caller's ambient transaction when one is active (propagation
     * REQUIRED) — a metering failure inside an existing @Transactional (e.g.
     * PostTransactionService.execute()) rolls back that transaction along with it, per this
     * codebase's single-transaction side-effect rule (see CLAUDE.md: "all side effects happen
     * in the same @Transactional as the primary operation").
     *
     * Callers with no ambient transaction (background/@Scheduled jobs — e.g.
     * ChainReaderOrchestrator, ApiCallCounterFlushJob) should wrap calls in `runCatching` at
     * the call site if a metering failure should not abort the primary operation; this service
     * does not swallow failures on their behalf.
     */
    fun recordUsage(
        tenantId: TenantId,
        metricType: MetricType,
        amount: Long = 1,
        idempotencyKey: String? = null,
    )

    fun getMonthlyUsage(
        tenantId: TenantId,
        yearMonth: YearMonth,
    ): UsageSummary
}
