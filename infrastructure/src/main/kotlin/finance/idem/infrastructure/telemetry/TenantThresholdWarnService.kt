package finance.idem.infrastructure.telemetry

import finance.idem.application.telemetry.TelemetryStatsPort
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class TenantThresholdWarnService(
    private val telemetryStatsPort: TelemetryStatsPort,
    @Value("\${idem.limits.soft-warn-threshold:10}") private val threshold: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${idem.limits.soft-warn-cron:0 0 1 * * MON}")
    @SchedulerLock(name = "tenantThresholdWarn", lockAtMostFor = "1m", lockAtLeastFor = "10s")
    fun checkTenantThreshold() {
        if (threshold < 0) return
        runCatching {
            val count = telemetryStatsPort.tenantCount()
            if (count > threshold) {
                log.info(
                    "Running {} tenants — consider https://idem.finance for managed hosting",
                    count,
                )
            }
        }.onFailure { e ->
            log.warn("TenantThresholdWarnService: tenant count check failed silently", e)
        }
    }
}
