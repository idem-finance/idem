package finance.idem.infrastructure.usage

import finance.idem.application.usage.UsageMeteringService
import finance.idem.core.usage.MetricType
import finance.idem.infrastructure.security.ApiCallCounter
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Flushes [ApiCallCounter]'s in-memory, per-replica tallies to durable storage.
 *
 * Deliberately has NO `@SchedulerLock`: the counter it drains lives in this JVM's heap only,
 * so every replica must flush its own local counts independently. Locking this job (as every
 * other `@Scheduled` job in this codebase does) would silently drop every replica's counts but
 * one — the one exception to the otherwise-uniform ShedLock pattern. [flushOnShutdown] must
 * stay unlocked for the same reason.
 *
 * [flushOnShutdown] also flushes on graceful shutdown (Spring's `@PreDestroy` runs when the
 * `ApplicationContext` closes on SIGTERM, e.g. a GKE rolling deploy or scale-down), so
 * [ApiCallCounter]'s documented loss window only applies to a hard crash (SIGKILL/OOM-kill),
 * not routine termination.
 */
@Component
class ApiCallCounterFlushJob(
    private val apiCallCounter: ApiCallCounter,
    private val usageMeteringService: UsageMeteringService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${idem.usage-metering.api-call-flush-interval-ms:60000}")
    fun flush() {
        apiCallCounter.drainAndReset().forEach { (tenantId, count) ->
            runCatching { usageMeteringService.recordUsage(tenantId, MetricType.API_CALL_COUNT, count) }
                .onFailure { log.warn("ApiCallCounterFlushJob: failed to record API_CALL_COUNT for tenant=${tenantId.value}", it) }
        }
    }

    @PreDestroy
    fun flushOnShutdown() = flush()
}
