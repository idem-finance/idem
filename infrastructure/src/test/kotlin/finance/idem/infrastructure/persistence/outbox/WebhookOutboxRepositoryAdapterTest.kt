package finance.idem.infrastructure.persistence.outbox

import finance.idem.application.outbox.OutboxStatus
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `save persists with status PENDING, zero attempts and an immediate next_retry_at`() {
        val entry = outboxEntry()
        adapter.save(entry)

        val pending = adapter.findPendingOrFailed(tenantA)
        assertEquals(1, pending.size)
        assertEquals(OutboxStatus.PENDING, pending[0].status)
        assertEquals(0, pending[0].attempts)
        assertTrue(!pending[0].nextRetryAt.isAfter(Instant.now()), "nextRetryAt should be immediately due")
        assertNull(pending[0].deliveredAt)
        assertEquals(entry.id, pending[0].id)
    }

    @Test
    fun `findPendingOrFailed returns PENDING and FAILED rows ordered by created_at ascending`() {
        val older = outboxEntry()
        val newer = outboxEntry()
        val delivered = outboxEntry()
        val dead = outboxEntry()

        // Insert with explicit timestamps/statuses to guarantee deterministic order and coverage
        val session = entityManager.unwrap(org.hibernate.Session::class.java)
        session.doWork { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantA.value}'")
            listOf(
                older to ("now() - interval '5 seconds'" to "PENDING"),
                newer to ("now()" to "FAILED"),
                delivered to ("now()" to "DELIVERED"),
                dead to ("now()" to "DEAD"),
            ).forEach { (e, tsAndStatus) ->
                val (ts, status) = tsAndStatus
                conn.prepareStatement(
                    "INSERT INTO webhook_outbox (id, tenant_id, transaction_id, event_type, payload, status, attempts, next_retry_at, created_at) " +
                        "VALUES (?::uuid, ?::uuid, ?::uuid, ?, '{}', ?, 0, now(), $ts)"
                ).use { stmt ->
                    stmt.setString(1, e.id.toString())
                    stmt.setString(2, tenantA.value.toString())
                    stmt.setString(3, e.transactionId.value.toString())
                    stmt.setString(4, e.eventType)
                    stmt.setString(5, status)
                    stmt.executeUpdate()
                }
            }
        }
        entityManager.clear()

        val pending = adapter.findPendingOrFailed(tenantA)
        assertEquals(2, pending.size)
        assertEquals(older.id, pending[0].id, "Older PENDING entry must come first")
        assertEquals(newer.id, pending[1].id, "Newer FAILED entry must come second")
        assertTrue(pending.none { it.id == delivered.id }, "DELIVERED rows must be excluded")
        assertTrue(pending.none { it.id == dead.id }, "DEAD rows must be excluded")
    }

    @Test
    fun `markDelivered sets status DELIVERED and delivered_at`() {
        val entry = outboxEntry()
        adapter.save(entry)

        adapter.markDelivered(entry.id, tenantA)

        // Clear the first-level cache so findById hits the DB, not the stale cached entity
        entityManager.flush()
        entityManager.clear()

        val pending = adapter.findPendingOrFailed(tenantA)
        assertEquals(0, pending.size)

        val row = adapter.jpaRepository.findById(entry.id).orElseThrow()
        assertEquals(OutboxStatus.DELIVERED, row.status)
        assertNotNull(row.deliveredAt)
    }

    @Test
    fun `markFailedForRetry sets status FAILED, increments attempts and records next_retry_at and last_error`() {
        val entry = outboxEntry()
        adapter.save(entry)

        val nextRetryAt = Instant.now().plusSeconds(30)
        adapter.markFailedForRetry(entry.id, tenantA, attempts = 1, nextRetryAt = nextRetryAt, lastError = "HTTP 500")

        entityManager.flush()
        entityManager.clear()

        val pending = adapter.findPendingOrFailed(tenantA)
        assertEquals(1, pending.size, "FAILED rows remain dispatchable")

        val row = adapter.jpaRepository.findById(entry.id).orElseThrow()
        assertEquals(OutboxStatus.FAILED, row.status)
        assertEquals(1, row.attempts)
        assertEquals("HTTP 500", row.lastError)
        assertEquals(nextRetryAt.epochSecond, row.nextRetryAt.epochSecond)
    }

    @Test
    fun `markDead sets status DEAD and last_error`() {
        val entry = outboxEntry()
        adapter.save(entry)

        adapter.markDead(entry.id, tenantA, lastError = "max attempts exceeded")

        entityManager.flush()
        entityManager.clear()

        val pending = adapter.findPendingOrFailed(tenantA)
        assertEquals(0, pending.size, "DEAD rows are no longer dispatchable")

        val row = adapter.jpaRepository.findById(entry.id).orElseThrow()
        assertEquals(OutboxStatus.DEAD, row.status)
        assertEquals("max attempts exceeded", row.lastError)
    }

    @Test
    fun `findPendingOrFailed is isolated by tenant (RLS)`() {
        adapter.save(outboxEntry(tenantA))
        adapter.save(outboxEntry(tenantB))

        val pendingA = adapter.findPendingOrFailed(tenantA)
        val pendingB = adapter.findPendingOrFailed(tenantB)

        assertEquals(1, pendingA.size)
        assertEquals(1, pendingB.size)
        assertEquals(tenantA.value, pendingA[0].tenantId)
        assertEquals(tenantB.value, pendingB[0].tenantId)
    }

    @Test
    fun `markDelivered does not affect other tenant rows`() {
        val entryA = outboxEntry(tenantA)
        val entryB = outboxEntry(tenantB)
        adapter.save(entryA)
        adapter.save(entryB)

        adapter.markDelivered(entryA.id, tenantA)

        assertEquals(1, adapter.findPendingOrFailed(tenantB).size)
    }
}
