package finance.idem.infrastructure.persistence.events

import finance.idem.application.events.DomainEvent
import finance.idem.core.TenantId
import finance.idem.core.events.DomainEventReferenceType
import finance.idem.core.events.DomainEventType
import finance.idem.infrastructure.SharedPostgresTestBase
import finance.idem.infrastructure.persistence.PersistenceTestConfig
import finance.idem.infrastructure.service.PostgresTestContainers
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DomainEventRepositoryAdapter::class, PersistenceTestConfig::class)
class DomainEventRepositoryAdapterTest : SharedPostgresTestBase() {
    companion object {
        private const val APP_ROLE = "idem_app_role_de"
        private const val APP_ROLE_PASSWORD = "app_role_pass"

        private val postgres get() = PostgresTestContainers.postgres

        fun ensureRestrictedRole() {
            // Runs after Flyway — called lazily from tests that need the restricted role
            DriverManager.getConnection(postgres.jdbcUrl, "idem", "idem").use { conn ->
                conn.createStatement().use { stmt ->
                    try {
                        stmt.execute("CREATE ROLE $APP_ROLE NOSUPERUSER LOGIN PASSWORD '$APP_ROLE_PASSWORD'")
                    } catch (_: SQLException) {
                        // Role already exists — idempotent
                    }
                    stmt.execute("GRANT CONNECT ON DATABASE idem_test TO $APP_ROLE")
                    stmt.execute("GRANT USAGE ON SCHEMA public TO $APP_ROLE")
                    // append-only: SELECT + INSERT only — no UPDATE or DELETE
                    stmt.execute("GRANT SELECT, INSERT ON domain_events TO $APP_ROLE")
                }
            }
        }

        fun restrictedConn() =
            DriverManager.getConnection(
                postgres.jdbcUrl,
                APP_ROLE,
                APP_ROLE_PASSWORD,
            )
    }

    @Autowired
    lateinit var adapter: DomainEventRepositoryAdapter

    @Autowired
    lateinit var entityManager: EntityManager

    private val tenantA = TenantId.generate()
    private val tenantB = TenantId.generate()

    private fun domainEvent(tenantId: TenantId = tenantA) =
        DomainEvent(
            id = UUID.randomUUID(),
            tenantId = tenantId,
            eventType = DomainEventType.TRANSACTION_COMMITTED,
            referenceId = UUID.randomUUID(),
            referenceType = DomainEventReferenceType.TRANSACTION,
            correlationId = "trace-1",
            occurredAt = Instant.now(),
        )

    @Test
    fun `save persists event with all fields populated`() {
        val event = domainEvent()
        adapter.save(event)

        entityManager.flush()
        entityManager.clear()

        entityManager.createNativeQuery("SET LOCAL app.tenant_id = '${tenantA.value}'").executeUpdate()
        val rows =
            entityManager
                .createNativeQuery(
                    "SELECT event_type, reference_id, reference_type, correlation_id FROM domain_events WHERE id = '${event.id}'",
                ).resultList
        assertEquals(1, rows.size)
        val row = rows[0] as Array<*>
        assertEquals("TRANSACTION_COMMITTED", row[0])
        assertEquals(event.referenceId.toString(), row[1].toString())
        assertEquals("TRANSACTION", row[2])
        assertEquals("trace-1", row[3])
    }

    @Test
    fun `RLS — each tenant sees only their own rows`() {
        adapter.save(domainEvent(tenantA))
        adapter.save(domainEvent(tenantB))

        entityManager.flush()
        entityManager.clear()

        val session = entityManager.unwrap(org.hibernate.Session::class.java)

        var countA = -1L
        session.doWork { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantA.value}'")
            val rs =
                conn
                    .createStatement()
                    .executeQuery("SELECT COUNT(*) FROM domain_events WHERE tenant_id = '${tenantA.value}'")
            rs.next()
            countA = rs.getLong(1)
        }

        var countB = -1L
        session.doWork { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantB.value}'")
            val rs =
                conn
                    .createStatement()
                    .executeQuery("SELECT COUNT(*) FROM domain_events WHERE tenant_id = '${tenantB.value}'")
            rs.next()
            countB = rs.getLong(1)
        }

        assertEquals(1L, countA, "Tenant A should see exactly its own 1 row")
        assertEquals(1L, countB, "Tenant B should see exactly its own 1 row")
    }

    @Test
    fun `domain_events is append-only — UPDATE denied for non-superuser role`() {
        ensureRestrictedRole()
        val event = domainEvent()
        adapter.save(event)
        entityManager.flush()

        restrictedConn().use { conn ->
            conn.autoCommit = false
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${event.tenantId.value}'")
            assertThrows<SQLException>("Non-superuser UPDATE must be denied") {
                conn.createStatement().executeUpdate(
                    "UPDATE domain_events SET event_type = 'WORKFLOW_COMMITTED' WHERE id = '${event.id}'",
                )
            }
            conn.rollback()
        }
    }

    @Test
    fun `domain_events is append-only — DELETE denied for non-superuser role`() {
        ensureRestrictedRole()
        val event = domainEvent()
        adapter.save(event)
        entityManager.flush()

        restrictedConn().use { conn ->
            conn.autoCommit = false
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${event.tenantId.value}'")
            assertThrows<SQLException>("Non-superuser DELETE must be denied") {
                conn.createStatement().executeUpdate(
                    "DELETE FROM domain_events WHERE id = '${event.id}'",
                )
            }
            conn.rollback()
        }
    }

    @Test
    fun `cross-tenant RLS isolation — non-superuser cannot read another tenant rows`() {
        ensureRestrictedRole()
        adapter.save(domainEvent(tenantA))
        entityManager.flush()

        restrictedConn().use { conn ->
            conn.autoCommit = false
            // Authenticate as tenantB — should not see tenantA's rows
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantB.value}'")
            val rs =
                conn
                    .createStatement()
                    .executeQuery("SELECT COUNT(*) FROM domain_events WHERE tenant_id = '${tenantA.value}'")
            rs.next()
            assertEquals(0L, rs.getLong(1), "Non-superuser with tenantB context must see 0 of tenantA rows")
            conn.rollback()
        }
    }
}
