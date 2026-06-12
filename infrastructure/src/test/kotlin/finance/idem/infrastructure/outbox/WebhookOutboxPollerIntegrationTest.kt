package finance.idem.infrastructure.outbox

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import finance.idem.application.outbox.OutboxStatus
import finance.idem.core.TenantId
import finance.idem.infrastructure.persistence.outbox.WebhookOutboxDataModel
import finance.idem.infrastructure.persistence.outbox.WebhookOutboxJpaRepository
import finance.idem.infrastructure.persistence.tenant.TenantDataModel
import finance.idem.infrastructure.persistence.tenant.TenantJpaRepository
import finance.idem.infrastructure.security.HmacSigner
import jakarta.annotation.PostConstruct
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Import(WebhookOutboxPollerIntegrationTest.SeedConfig::class)
class WebhookOutboxPollerIntegrationTest {

    companion object {
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
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
            registry.add("idem.webhook.poll-interval-ms") { "200" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            wireMock.stop()
        }
    }

    /**
     * Seeds one `tenants` row + one `webhook_outbox` row per scenario (A-D)
     * before the WebhookOutboxPoller's scheduler starts, and stubs WireMock
     * accordingly. Each scenario uses its own tenant and webhook path so the
     * single shared poller can dispatch all of them independently.
     */
    @TestConfiguration
    @EnableScheduling
    class SeedConfig(
        private val tenantJpaRepository: TenantJpaRepository,
        private val webhookOutboxJpaRepository: WebhookOutboxJpaRepository,
    ) {
        val tenantA: TenantId = TenantId.generate() // delivered on first attempt
        val tenantB: TenantId = TenantId.generate() // HTTP 500 -> retry with backoff
        val tenantC: TenantId = TenantId.generate() // already at attempts=4 -> DEAD on next failure
        val tenantD: TenantId = TenantId.generate() // no tenants row -- not configured

        val rowA: UUID = UUID.randomUUID()
        val rowB: UUID = UUID.randomUUID()
        val rowC: UUID = UUID.randomUUID()
        val rowD: UUID = UUID.randomUUID()

        val secretA = "secret-a"
        val secretB = "secret-b"
        val secretC = "secret-c"

        val payloadA = """{"eventType":"transaction.committed","scenario":"a"}"""
        val payloadB = """{"eventType":"transaction.committed","scenario":"b"}"""
        val payloadC = """{"eventType":"transaction.committed","scenario":"c"}"""
        val payloadD = """{"eventType":"transaction.committed","scenario":"d"}"""

        @PostConstruct
        fun seed() {
            wireMock.stubFor(post(urlPathEqualTo("/webhook/a")).willReturn(aResponse().withStatus(200)))
            wireMock.stubFor(post(urlPathEqualTo("/webhook/b")).willReturn(aResponse().withStatus(500)))
            wireMock.stubFor(post(urlPathEqualTo("/webhook/c")).willReturn(aResponse().withStatus(500)))

            val now = Instant.now()
            tenantJpaRepository.save(TenantDataModel(id = tenantA.value, webhookUrl = "http://localhost:${wireMock.port()}/webhook/a", webhookSecret = secretA, createdAt = now, updatedAt = now))
            tenantJpaRepository.save(TenantDataModel(id = tenantB.value, webhookUrl = "http://localhost:${wireMock.port()}/webhook/b", webhookSecret = secretB, createdAt = now, updatedAt = now))
            tenantJpaRepository.save(TenantDataModel(id = tenantC.value, webhookUrl = "http://localhost:${wireMock.port()}/webhook/c", webhookSecret = secretC, createdAt = now, updatedAt = now))
            // tenantD: deliberately no tenants row -- "not configured yet"

            webhookOutboxJpaRepository.save(outboxRow(rowA, tenantA, payloadA, OutboxStatus.PENDING, attempts = 0))
            webhookOutboxJpaRepository.save(outboxRow(rowB, tenantB, payloadB, OutboxStatus.PENDING, attempts = 0))
            webhookOutboxJpaRepository.save(outboxRow(rowC, tenantC, payloadC, OutboxStatus.FAILED, attempts = 4))
            webhookOutboxJpaRepository.save(outboxRow(rowD, tenantD, payloadD, OutboxStatus.PENDING, attempts = 0))
        }

        private fun outboxRow(id: UUID, tenantId: TenantId, payload: String, status: OutboxStatus, attempts: Int): WebhookOutboxDataModel {
            val now = Instant.now()
            return WebhookOutboxDataModel(
                id = id,
                tenantId = tenantId.value,
                transactionId = UUID.randomUUID(),
                eventType = "transaction.committed",
                payload = payload,
                status = status,
                attempts = attempts,
                nextRetryAt = now,
                lastError = null,
                createdAt = now,
                deliveredAt = null,
            )
        }
    }

    @Autowired
    lateinit var seed: SeedConfig

    @Autowired
    lateinit var webhookOutboxJpaRepository: WebhookOutboxJpaRepository

    @Test
    fun `scenario A - delivers successfully and signs with the tenant's secret`() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            val row = webhookOutboxJpaRepository.findById(seed.rowA).orElseThrow()
            assertEquals(OutboxStatus.DELIVERED, row.status)
            assertNotNull(row.deliveredAt)
        }

        val requests = wireMock.findAll(postRequestedFor(urlPathEqualTo("/webhook/a")))
        assertEquals(1, requests.size)

        // Read back the persisted payload rather than seed.payloadA -- Postgres'
        // jsonb column normalizes the JSON text (e.g. key order), so the bytes
        // actually signed/transmitted are the persisted form, not the literal
        // string that was inserted.
        val persistedPayload = webhookOutboxJpaRepository.findById(seed.rowA).orElseThrow().payload
        assertEquals(persistedPayload, requests[0].bodyAsString)
        val expectedSignature = "sha256=" + HmacSigner.hexHmacSha256(seed.secretA, persistedPayload)
        assertEquals(expectedSignature, requests[0].getHeader("X-Idem-Signature"))
    }

    @Test
    fun `scenario B - non-2xx response schedules a retry with backoff`() {
        val before = Instant.now()

        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            val row = webhookOutboxJpaRepository.findById(seed.rowB).orElseThrow()
            assertEquals(OutboxStatus.FAILED, row.status)
            assertEquals(1, row.attempts)
        }

        val row = webhookOutboxJpaRepository.findById(seed.rowB).orElseThrow()
        assertEquals("HTTP 500", row.lastError)
        assertTrue(row.nextRetryAt.isAfter(before.plusSeconds(3)), "next_retry_at should be ~5s out")
        assertTrue(row.nextRetryAt.isBefore(before.plusSeconds(8)), "next_retry_at should be ~5s out")
    }

    @Test
    fun `scenario C - reaching maxAttempts marks the row DEAD`() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted {
            val row = webhookOutboxJpaRepository.findById(seed.rowC).orElseThrow()
            assertEquals(OutboxStatus.DEAD, row.status)
        }

        val row = webhookOutboxJpaRepository.findById(seed.rowC).orElseThrow()
        assertEquals("HTTP 500", row.lastError)
        assertEquals(4, row.attempts, "markDead does not change attempts")
    }

    @Test
    fun `scenario D - row for an unconfigured tenant stays PENDING`() {
        await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2)).untilAsserted {
            val row = webhookOutboxJpaRepository.findById(seed.rowD).orElseThrow()
            assertEquals(OutboxStatus.PENDING, row.status)
            assertEquals(0, row.attempts)
        }

        assertEquals(0, wireMock.findAll(postRequestedFor(urlPathEqualTo("/webhook/d"))).size)
    }
}
