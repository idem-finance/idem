package finance.idem.infrastructure.usage

import finance.idem.core.usage.UsageMetricRepository
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Aggregates raw `usage_metrics` events into hourly `usage_metrics_hourly` buckets, advancing
 * a single watermark one hour at a time. `rollupHour` is idempotent (`ON CONFLICT DO NOTHING`),
 * so if this run fails partway through — after committing some hours but before advancing past
 * a later one — the next run simply resumes from the watermark last successfully committed,
 * re-processing (harmlessly) at most the in-flight hour.
 *
 * [maxHoursPerRun] bounds how many hours a single invocation processes, so a large backlog
 * (an extended outage, or a stalled watermark) can't make one run exceed the `@SchedulerLock`
 * lease (`lockAtMostFor = "10m"`) — which would let a second replica acquire the lock
 * concurrently mid-run. A bounded run simply leaves the remainder for the next scheduled
 * run (this job runs hourly) rather than risking the lock; the watermark it leaves behind is
 * always a real, safe resume point either way.
 */
@Component
class UsageMetricsRollupJob(
    private val usageMetricRepository: UsageMetricRepository,
    @Value("\${idem.usage-metering.rollup-safety-buffer-minutes:5}") private val safetyBufferMinutes: Long,
    @Value("\${idem.usage-metering.rollup-max-hours-per-run:120}") private val maxHoursPerRun: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${idem.usage-metering.rollup-cron:0 5 * * * *}")
    @SchedulerLock(name = "usageMetricsRollup", lockAtMostFor = "10m", lockAtLeastFor = "30s")
    fun rollup() {
        runCatching {
            // Only fully-elapsed hours older than the safety buffer are eligible, so an
            // in-flight transaction from just before an hour boundary has time to commit
            // before its event would otherwise be missed by this hour's rollup.
            val cutoff = Instant.now().minus(Duration.ofMinutes(safetyBufferMinutes))
            var hourStart = usageMetricRepository.currentWatermark()
            var processed = 0
            while (processed < maxHoursPerRun) {
                val hourEnd = hourStart.plus(Duration.ofHours(1))
                if (hourEnd.isAfter(cutoff)) break
                usageMetricRepository.rollupHour(hourStart, hourEnd)
                usageMetricRepository.advanceWatermark(hourEnd)
                hourStart = hourEnd
                processed++
            }
        }.onFailure { log.error("UsageMetricsRollupJob: rollup failed, will resume from last committed watermark next run", it) }
    }
}
