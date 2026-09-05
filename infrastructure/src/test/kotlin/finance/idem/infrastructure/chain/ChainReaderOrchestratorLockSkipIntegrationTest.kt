package finance.idem.infrastructure.chain

import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
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
import javax.sql.DataSource
import kotlin.test.assertTrue

/**
 * Isolated from [ChainReaderOrchestratorIntegrationTest] (idem#302): that class enables
 * scheduling with a short 200ms polling interval so its other scenarios see prompt Tron
 * polling, but that also means the real `@Scheduled` `pollTron()` trigger races this
 * lock-skip scenario for the ShedLock row -- the lock is only actually acquirable during a
 * ~200ms window that recurs every ~4.2s (lockAtLeastFor="4s"), so the test was gambling on
 * landing in that window before its own `await()` timeout. This class never enables
 * scheduling at all, so the real trigger can never fire; it instead calls
 * [ChainReaderOrchestrator.pollTron] directly and synchronously through the injected
 * Spring-managed bean -- `@SchedulerLock`'s AOP interceptor still sees the externally-held
 * lock and skips the method body, deterministically, with no wall-clock racing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Import(ChainReaderOrchestratorLockSkipIntegrationTest.FakeTronReaderConfig::class)
class ChainReaderOrchestratorLockSkipIntegrationTest {
    companion object {
        @Container
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16")
                .withDatabaseName("idem_test")
                .withUsername("idem")
                .withPassword("idem")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("idem.scheduling.distributed-lock.enabled") { "true" }
        }
    }

    @TestConfiguration
    class FakeTronReaderConfig {
        @Bean
        fun fakeTronReader(): ChainReader =
            mock<ChainReader>().also {
                whenever(it.chainKey).thenReturn("TRON")
                whenever(it.poll(any())).thenReturn(emptyList())
            }

        @Bean
        @Primary
        fun fakeChainReaderList(fakeTronReader: ChainReader): List<ChainReader> = listOf(fakeTronReader)
    }

    @Autowired
    @Qualifier("fakeTronReader")
    lateinit var fakeTronReader: ChainReader

    @Autowired
    lateinit var chainReaderOrchestrator: ChainReaderOrchestrator

    @Autowired
    lateinit var dataSource: DataSource

    @Test
    fun `pollTron is skipped while another replica holds the pollTron lock`() {
        // Build a second LockProvider that identifies as "other-replica".
        // ShedLock's unlock() uses AND locked_by = :lockedBy, so only the holder can release.
        val otherReplicaLock =
            JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration
                    .builder()
                    .withJdbcTemplate(JdbcTemplate(dataSource))
                    .usingDbTime()
                    .withLockedByValue("other-replica")
                    .build(),
            )

        // Nothing else competes for this lock -- scheduling is never enabled in this context,
        // so the real pollTron() trigger can never fire. Acquire deterministically, no await().
        val heldLock =
            otherReplicaLock.lock(
                LockConfiguration(Instant.now(), "pollTron", Duration.ofSeconds(30), Duration.ZERO),
            )
        assertTrue(heldLock.isPresent, "test setup: must be able to take the lock when nothing else holds it")

        try {
            // Direct, synchronous call through the Spring-managed bean -- ShedLock's AOP
            // interceptor sees the lock already held by "other-replica" and skips the method
            // body entirely; assert the underlying reader was never polled.
            chainReaderOrchestrator.pollTron()
            verify(fakeTronReader, never()).poll(any())
        } finally {
            heldLock.get().unlock()
        }

        // Lock released -- calling pollTron() again must now run the method body to completion.
        chainReaderOrchestrator.pollTron()
        verify(fakeTronReader, times(1)).poll(any())
    }
}
