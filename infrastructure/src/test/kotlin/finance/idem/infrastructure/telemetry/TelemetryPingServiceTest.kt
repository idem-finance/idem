package finance.idem.infrastructure.telemetry

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import finance.idem.application.telemetry.InstallationMetadataPort
import finance.idem.application.telemetry.TelemetryStatsPort
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID
import kotlin.test.assertEquals

class TelemetryPingServiceTest {

    private val installationMetadataPort: InstallationMetadataPort = mock()
    private val telemetryStatsPort: TelemetryStatsPort = mock()
    private val objectMapper = ObjectMapper().registerKotlinModule()

    private fun service(endpoint: String = "http://localhost:1/ping") = TelemetryPingService(
        installationMetadataPort = installationMetadataPort,
        telemetryStatsPort = telemetryStatsPort,
        objectMapper = objectMapper,
        endpoint = endpoint,
    )

    @ParameterizedTest(name = "count={0} → bucket={1}")
    @CsvSource(
        "0,  1",
        "1,  1",
        "2,  2-10",
        "10, 2-10",
        "11, 11-50",
        "50, 11-50",
        "51, 50+",
        "999, 50+",
    )
    fun `bucket maps counts to correct label`(count: Long, expected: String) {
        assertEquals(expected, service().bucket(count))
    }

    @Test
    fun `ping does not propagate when installationMetadataPort throws`() {
        whenever(installationMetadataPort.getOrCreateId()).thenThrow(RuntimeException("db down"))

        // should not throw
        service().ping()
    }

    @Test
    fun `ping does not propagate when HTTP connection is refused`() {
        whenever(installationMetadataPort.getOrCreateId()).thenReturn(UUID.randomUUID())
        whenever(telemetryStatsPort.tenantCount()).thenReturn(1L)
        whenever(telemetryStatsPort.journalLineCount()).thenReturn(5L)

        // port 1 is always connection-refused — exercises the runCatching.onFailure path
        service(endpoint = "http://localhost:1/ping").ping()
    }

    @Test
    fun `ping does not propagate when telemetryStatsPort throws`() {
        whenever(installationMetadataPort.getOrCreateId()).thenReturn(UUID.randomUUID())
        whenever(telemetryStatsPort.tenantCount()).thenThrow(RuntimeException("query failed"))

        service().ping()
    }
}
