package finance.idem.infrastructure.outbox

import finance.idem.application.outbox.WebhookOutboxDispatch
import finance.idem.application.port.TenantRepository
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.application.usage.UsageMeteringService
import finance.idem.core.usage.MetricType
import finance.idem.infrastructure.security.HmacSigner
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

@Component
class WebhookOutboxPoller(
    private val webhookOutboxRepository: WebhookOutboxRepository,
    private val tenantRepository: TenantRepository,
    private val httpClient: HttpClient,
    private val urlValidator: WebhookUrlValidator,
    private val usageMeteringService: UsageMeteringService,
    @Value("\${idem.webhook.timeout-ms:5000}") private val timeoutMs: Long,
    @Value("\${idem.webhook.max-attempts:5}") private val maxAttempts: Int,
    @Value("\${idem.webhook.batch-size:50}") private val batchSize: Int,
) {
    init {
        require(maxAttempts in 1..RetrySchedule.MAX_SUPPORTED_ATTEMPTS) {
            "idem.webhook.max-attempts must be between 1 and ${RetrySchedule.MAX_SUPPORTED_ATTEMPTS} " +
                "(RetrySchedule defines backoff delays for attempts 1..${RetrySchedule.MAX_SUPPORTED_ATTEMPTS - 1}); got $maxAttempts"
        }
    }

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${idem.webhook.poll-interval-ms:5000}")
    @SchedulerLock(name = "webhookOutboxPoll", lockAtMostFor = "5m", lockAtLeastFor = "4s")
    fun poll() {
        val rows =
            runCatching { webhookOutboxRepository.findDispatchable(batchSize) }
                .onFailure { log.error("WebhookOutboxPoller: failed to fetch dispatchable rows", it) }
                .getOrNull() ?: return

        rows.forEach { dispatch(it) }
    }

    private fun dispatch(entry: WebhookOutboxDispatch) {
        val config =
            runCatching { tenantRepository.findWebhookConfig(entry.tenantId) }
                .onFailure { log.error("WebhookOutboxPoller: failed to load webhook config for tenant=${entry.tenantId.value}", it) }
                .getOrNull()

        if (config == null) {
            log.debug("WebhookOutboxPoller: no webhook configured for tenant={} -- leaving id={} PENDING", entry.tenantId.value, entry.id)
            return
        }

        // SSRF guard — mark dead immediately, never retry a malicious URL
        urlValidator.validate(config.webhookUrl).onFailure { e ->
            log.warn("WebhookOutboxPoller: SSRF-blocked delivery id={} tenant={}: {}", entry.id, entry.tenantId.value, e.message)
            runCatching { webhookOutboxRepository.markDead(entry.id, entry.tenantId, "SSRF_BLOCKED: ${e.message}") }
                .onFailure { ex -> log.error("WebhookOutboxPoller: failed to mark SSRF-blocked row id=${entry.id} as DEAD", ex) }
            return
        }

        val attemptResult =
            runCatching {
                val signature = HmacSigner.hexHmacSha256(config.webhookSecret, entry.payload)
                val request =
                    HttpRequest
                        .newBuilder()
                        .uri(URI.create(config.webhookUrl))
                        .timeout(Duration.ofMillis(timeoutMs))
                        .header("Content-Type", "application/json")
                        .header("X-Idem-Signature", "sha256=$signature")
                        .POST(HttpRequest.BodyPublishers.ofString(entry.payload))
                        .build()
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            }

        runCatching {
            attemptResult.fold(
                onSuccess = { response ->
                    if (response.statusCode() in 200..299) {
                        webhookOutboxRepository.markDelivered(entry.id, entry.tenantId)
                        runCatching { usageMeteringService.recordUsage(entry.tenantId, MetricType.WEBHOOK_DELIVERY_COUNT) }
                            .onFailure { log.warn("WebhookOutboxPoller: failed to record WEBHOOK_DELIVERY_COUNT for id=${entry.id}", it) }
                    } else {
                        handleFailure(entry, "HTTP ${response.statusCode()}")
                    }
                },
                onFailure = { e -> handleFailure(entry, e.message ?: e.javaClass.simpleName) },
            )
        }.onFailure { e -> log.error("WebhookOutboxPoller: failed to update outbox row id=${entry.id}", e) }
    }

    private fun handleFailure(
        entry: WebhookOutboxDispatch,
        error: String,
    ) {
        val attempts = entry.attempts + 1
        val delay = RetrySchedule.nextRetryDelay(attempts, maxAttempts)
        if (delay == null) {
            webhookOutboxRepository.markDead(entry.id, entry.tenantId, error)
        } else {
            webhookOutboxRepository.markFailedForRetry(entry.id, entry.tenantId, attempts, Instant.now().plus(delay), error)
        }
    }
}
