package finance.idem.infrastructure.telemetry

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import finance.idem.infrastructure.persistence.telemetry.InstallationMetadataJpaRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class TelemetryPingServiceIntegrationTest {
    companion object {
        @Container
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16")
                .withDatabaseName("idem_test")
                .withUsername("idem")
                .withPassword("idem")

        val wireMock: WireMockServer = WireMockServer(wireMockConfig().dynamicPort())

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            wireMock.start()
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("idem.telemetry.endpoint") { "http://localhost:${wireMock.port()}/ping" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            wireMock.stop()
        }
    }

    @BeforeEach
    fun resetWireMock() {
        wireMock.resetRequests()
    }

    @Autowired
    lateinit var telemetryPingService: TelemetryPingService

    @Autowired
    lateinit var installationMetadataJpaRepository: InstallationMetadataJpaRepository

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `ping sends POST to telemetry endpoint with valid JSON payload`() {
        wireMock.stubFor(post(urlPathEqualTo("/ping")).willReturn(aResponse().withStatus(204)))

        telemetryPingService.ping()

        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/ping")))
        val request = wireMock.allServeEvents.first().request
        assertEquals("application/json", request.getHeader("Content-Type"))

        val body = objectMapper.readTree(request.bodyAsString)
        assertNotNull(body.get("installationId"))
        assertNotNull(body.get("idemVersion"))
        assertNotNull(body.get("javaVersion"))
        assertTrue(body.get("tenantBucket").asText().isNotBlank())
        assertTrue(body.get("entryBucket").asText().isNotBlank())
    }

    @Test
    fun `ping persists installation_metadata row on first call`() {
        wireMock.stubFor(post(urlPathEqualTo("/ping")).willReturn(aResponse().withStatus(204)))

        telemetryPingService.ping()

        assertEquals(1, installationMetadataJpaRepository.count())
    }

    @Test
    fun `ping returns same installation UUID on repeated calls`() {
        wireMock.stubFor(post(urlPathEqualTo("/ping")).willReturn(aResponse().withStatus(204)))

        telemetryPingService.ping()
        telemetryPingService.ping()

        assertEquals(1, installationMetadataJpaRepository.count())
    }

    @Test
    fun `ping does not throw when server returns 500`() {
        wireMock.stubFor(post(urlPathEqualTo("/ping")).willReturn(aResponse().withStatus(500)))

        // Should complete silently with a warn log
        telemetryPingService.ping()
    }

    @Nested
    @SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = ["idem.telemetry.enabled=false"],
    )
    inner class WhenTelemetryDisabled {
        @Autowired
        lateinit var applicationContext: ApplicationContext

        @Test
        fun `TelemetryPingService bean is absent when telemetry is disabled`() {
            assertFalse(applicationContext.containsBean("telemetryPingService"))
        }
    }
}
