package finance.idem.infrastructure.persistence.outbox

import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.infrastructure.persistence.PersistenceTestConfig
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(WebhookOutboxRepositoryAdapter::class, PersistenceTestConfig::class)
class WebhookOutboxRepositoryAdapterTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:16")
            .withDatabaseName("idem_test")
            .withUsername("idem")
            .withPassword("idem")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired
    lateinit var adapter: WebhookOutboxRepositoryAdapter

    @Autowired
    lateinit var entityManager: EntityManager

    private val tenantA = TenantId.generate()
    private val tenantB = TenantId.generate()

    private fun outboxEntry(tenantId: TenantId = tenantA) = WebhookOutboxEntry(
        id = UUID.randomUUID(),
        tenantId = tenantId,
        eventType = "transaction.committed",
        transactionId = TransactionId.generate(),
        occurredAt = Instant.now(),
    )

    @Test
    fun `save persists with dispatched false`() {
        val entry = outboxEntry()
        adapter.save(entry)

        val pending = adapter.findPending(tenantA)
        assertEquals(1, pending.size)
        assertFalse(pending[0].dispatched)
        assertEquals(entry.id, pending[0].id)
    }

    @Test
    fun `findPending returns only undispatched rows`() {
        val first = outboxEntry()
        val second = outboxEntry()
        adapter.save(first)
        adapter.save(second)

        val pending = adapter.findPending(tenantA)
        assertEquals(2, pending.size)
        assertTrue(pending.map { it.id }.containsAll(listOf(first.id, second.id)))
    }

    @Test
    fun `markDispatched sets dispatched true and dispatched_at`() {
        val entry = outboxEntry()
        adapter.save(entry)

        adapter.markDispatched(entry.id, tenantA)

        // Clear the first-level cache so findById hits the DB, not the stale cached entity
        entityManager.flush()
        entityManager.clear()

        val pending = adapter.findPending(tenantA)
        assertEquals(0, pending.size)

        val row = adapter.jpaRepository.findById(entry.id).orElseThrow()
        assertTrue(row.dispatched)
        assertNotNull(row.dispatchedAt)
    }

    @Test
    fun `findPending is isolated by tenant (RLS)`() {
        adapter.save(outboxEntry(tenantA))
        adapter.save(outboxEntry(tenantB))

        val pendingA = adapter.findPending(tenantA)
        val pendingB = adapter.findPending(tenantB)

        assertEquals(1, pendingA.size)
        assertEquals(1, pendingB.size)
        assertEquals(tenantA.value, pendingA[0].tenantId)
        assertEquals(tenantB.value, pendingB[0].tenantId)
    }

    @Test
    fun `markDispatched does not affect other tenant rows`() {
        val entryA = outboxEntry(tenantA)
        val entryB = outboxEntry(tenantB)
        adapter.save(entryA)
        adapter.save(entryB)

        adapter.markDispatched(entryA.id, tenantA)

        assertEquals(1, adapter.findPending(tenantB).size)
    }
}
