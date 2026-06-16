package finance.idem.infrastructure.telemetry

import com.fasterxml.jackson.databind.ObjectMapper
import finance.idem.application.telemetry.InstallationMetadataPort
import finance.idem.application.telemetry.TelemetryStatsPort
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.info.BuildProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
@ConditionalOnProperty(name = ["idem.telemetry.enabled"], matchIfMissing = true)
class TelemetryPingService(
    private val installationMetadataPort: InstallationMetadataPort,
    private val telemetryStatsPort: TelemetryStatsPort,
    private val objectMapper: ObjectMapper,
    @Value("\${idem.telemetry.endpoint:https://telemetry.idem.finance/ping}")
    private val endpoint: String,
    private val buildProperties: BuildProperties? = null,
) {
    private val httpClient: HttpClient = HttpClient.newHttpClient()
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${idem.telemetry.cron:0 0 1 1 * *}")
    @SchedulerLock(name = "telemetryPing", lockAtMostFor = "1m", lockAtLeastFor = "10s")
    fun ping() {
        runCatching {
            val payload = objectMapper.writeValueAsString(
                mapOf(
                    "installationId" to installationMetadataPort.getOrCreateId().toString(),
                    "idemVersion"    to (buildProperties?.version ?: "unknown"),
                    "javaVersion"    to (System.getProperty("java.version") ?: "unknown"),
                    "tenantBucket"   to bucket(telemetryStatsPort.tenantCount()),
                    "entryBucket"    to bucket(telemetryStatsPort.journalLineCount()),
                )
            )
            val request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                log.warn("TelemetryPingService: server returned HTTP {}", response.statusCode())
            }
        }.onFailure { e ->
            log.warn("TelemetryPingService: ping failed silently", e)
        }
    }

    internal fun bucket(count: Long): String = when {
        count <= 1L  -> "1"
        count <= 10L -> "2-10"
        count <= 50L -> "11-50"
        else         -> "50+"
    }
}
