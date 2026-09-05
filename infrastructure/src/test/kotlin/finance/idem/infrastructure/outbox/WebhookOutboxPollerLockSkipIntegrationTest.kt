package finance.idem.infrastructure.outbox

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import finance.idem.application.outbox.OutboxStatus
import finance.idem.core.TenantId
import finance.idem.infrastructure.outbox.WebhookUrlValidator
import finance.idem.infrastructure.persistence.outbox.WebhookOutboxDataModel
import finance.idem.infrastructure.persistence.outbox.WebhookOutboxJpaRepository
import finance.idem.infrastructure.persistence.tenant.TenantDataModel
import finance.idem.infrastructure.persistence.tenant.TenantJpaRepository
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Isolated from [WebhookOutboxPollerIntegrationTest] (idem#302): that class enables scheduling
 * with a short 200ms poll interval so its other scenarios see prompt delivery, but that also
 * means the real `@Scheduled` `poll()` trigger races this lock-skip scenario for the ShedLock
 * row -- the lock is only actually acquirable during a ~200ms window that recurs every ~4.2s
 * (lockAtLeastFor="4s"), so the test was gambling on landing in that window before its own
 * `await()` timeout. This class never enables scheduling at all, so the real trigger can never
 * fire; it instead calls [WebhookOutboxPoller.poll] directly and synchronously through the
 * injected Spring-managed bean -- `@SchedulerLock`'s AOP interceptor still sees the
 * externally-held lock and skips the method body, deterministically, with no wall-clock racing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Import(WebhookOutboxPollerLockSkipIntegrationTest.NoScheduleConfig::class)
class WebhookOutboxPollerLockSkipIntegrationTest {
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
            registry.add("idem.scheduling.distributed-lock.enabled") { "true" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            wireMock.stop()
        }
    }

    @TestConfiguration
    class NoScheduleConfig {
        // WireMock runs on localhost (http) — bypass SSRF protection in this integration suite.
        // SSRF validation is covered by SsrfWebhookUrlValidatorTest.
        @Bean
        @Primary
        fun webhookUrlValidator(): WebhookUrlValidator = WebhookUrlValidator { Result.success(Unit) }
    }

    @Autowired
    lateinit var webhookOutboxPoller: WebhookOutboxPoller

    @Autowired
    lateinit var webhookOutboxJpaRepository: WebhookOutboxJpaRepository

    @Autowired
    lateinit var tenantJpaRepository: TenantJpaRepository

    @Autowired
    lateinit var dataSource: DataSource

    @Test
    fun `webhookOutboxPoll is skipped while another replica holds the webhookOutboxPoll lock`() {
        // Use a second LockProvider that identifies as "other-replica".
        // ShedLock's unlock() uses AND locked_by = :lockedBy, so only the holder can release,
        // preventing the production scheduler from inadvertently dropping our hold.
        val otherReplicaLock =
            JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration
                    .builder()
                    .withJdbcTemplate(JdbcTemplate(dataSource))
                    .usingDbTime()
                    .withLockedByValue("other-replica")
                    .build(),
            )

        // Nothing else competes for this lock -- the real scheduler's poll interval is set to an
        // hour above, so it cannot tick during this test. Acquire deterministically, no await().
        val heldLock =
            otherReplicaLock.lock(
                LockConfiguration(Instant.now(), "webhookOutboxPoll", Duration.ofSeconds(30), Duration.ZERO),
            )
        assertTrue(heldLock.isPresent, "test setup: must be able to take the lock when nothing else holds it")

        val tenantId = TenantId.generate()
        val rowId = UUID.randomUUID()
        val now = Instant.now()
        wireMock.stubFor(post(urlPathEqualTo("/webhook/lock")).willReturn(aResponse().withStatus(200)))
        tenantJpaRepository.save(
            TenantDataModel(
                id = tenantId.value,
                webhookUrl = "http://localhost:${wireMock.port()}/webhook/lock",
                webhookSecret = "secret-lock",
                createdAt = now,
                updatedAt = now,
            ),
        )
        webhookOutboxJpaRepository.save(
            WebhookOutboxDataModel(
                id = rowId,
                tenantId = tenantId.value,
                transactionId = UUID.randomUUID(),
                eventType = "transaction.committed",
                payload = """{"eventType":"transaction.committed","scenario":"lock"}""",
                status = OutboxStatus.PENDING,
                attempts = 0,
                nextRetryAt = now,
                lastError = null,
                createdAt = now,
                deliveredAt = null,
            ),
        )

        try {
            // Direct, synchronous call through the Spring-managed bean -- ShedLock's AOP
            // interceptor sees the lock already held by "other-replica" and skips the method
            // body entirely; assert nothing was delivered.
            webhookOutboxPoller.poll()

            val row = webhookOutboxJpaRepository.findById(rowId).orElseThrow()
            assertEquals(OutboxStatus.PENDING, row.status, "poll() must be skipped while another replica holds the lock")
            assertEquals(0, row.attempts)
        } finally {
            heldLock.get().unlock()
        }

        // Lock released -- calling poll() again must now run the method body to completion.
        webhookOutboxPoller.poll()
        val row = webhookOutboxJpaRepository.findById(rowId).orElseThrow()
        assertEquals(OutboxStatus.DELIVERED, row.status)
    }
}
