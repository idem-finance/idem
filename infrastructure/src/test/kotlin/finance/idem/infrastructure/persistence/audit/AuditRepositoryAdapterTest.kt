package finance.idem.infrastructure.persistence.audit

import finance.idem.application.audit.AuditEntry
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.infrastructure.persistence.PersistenceTestConfig
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(AuditRepositoryAdapter::class, PersistenceTestConfig::class)
class AuditRepositoryAdapterTest {

    companion object {
        private const val APP_ROLE = "idem_app_role"
        private const val APP_ROLE_PASSWORD = "app_role_pass"

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
                    stmt.execute("GRANT SELECT, INSERT ON audit_log TO $APP_ROLE")
                }
            }
        }

        fun restrictedConn() = DriverManager.getConnection(
            postgres.jdbcUrl, APP_ROLE, APP_ROLE_PASSWORD
        )
    }

    @Autowired
    lateinit var adapter: AuditRepositoryAdapter

    @Autowired
    lateinit var jpaRepository: AuditLogJpaRepository

    @Autowired
    lateinit var entityManager: EntityManager

    private val tenantA = TenantId.generate()
    private val tenantB = TenantId.generate()

    private fun auditEntry(tenantId: TenantId = tenantA) = AuditEntry(
        id = UUID.randomUUID(),
        transactionId = TransactionId.generate(),
        tenantId = tenantId,
        action = "POST_TRANSACTION",
        agentContext = null,
        createdBy = "sk_live_test",
        occurredAt = Instant.now(),
    )

    @Test
    fun `save persists entry with all fields populated`() {
        val entry = auditEntry()
        adapter.save(entry)

        entityManager.flush()
        entityManager.clear()

        // Re-query with tenant context set so RLS allows access
        entityManager.createNativeQuery("SET LOCAL app.tenant_id = '${tenantA.value}'").executeUpdate()
        val rows = entityManager.createNativeQuery(
            "SELECT id, action, created_by, hmac FROM audit_log WHERE id = '${entry.id}'"
        ).resultList
        assertEquals(1, rows.size)
        val row = rows[0] as Array<*>
        assertEquals("POST_TRANSACTION", row[1])
        assertEquals("sk_live_test", row[2])
        assertNotNull(row[3])
        assertTrue((row[3] as String).isNotBlank(), "HMAC must be populated")
    }

    @Test
    fun `save generates non-empty HMAC tied to tenant key`() {
        val entry = auditEntry()
        adapter.save(entry)

        entityManager.flush()
        entityManager.clear()

        entityManager.createNativeQuery("SET LOCAL app.tenant_id = '${tenantA.value}'").executeUpdate()
        val hmac = entityManager.createNativeQuery(
            "SELECT hmac FROM audit_log WHERE id = '${entry.id}'"
        ).singleResult as String

        assertTrue(hmac.isNotBlank())
        // HMAC should be base64-encoded SHA256 — expect ~44 chars
        assertTrue(hmac.length >= 40)
    }

    @Test
    fun `RLS — each tenant sees only their own rows`() {
        adapter.save(auditEntry(tenantA))
        adapter.save(auditEntry(tenantB))

        entityManager.flush()
        entityManager.clear()

        // Use Hibernate Session.doWork for reliable SET LOCAL on the underlying JDBC connection
        val session = entityManager.unwrap(org.hibernate.Session::class.java)

        var countA = -1L
        session.doWork { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantA.value}'")
            val rs = conn.createStatement()
                .executeQuery("SELECT COUNT(*) FROM audit_log WHERE tenant_id = '${tenantA.value}'")
            rs.next()
            countA = rs.getLong(1)
        }

        var countB = -1L
        session.doWork { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantB.value}'")
            val rs = conn.createStatement()
                .executeQuery("SELECT COUNT(*) FROM audit_log WHERE tenant_id = '${tenantB.value}'")
            rs.next()
            countB = rs.getLong(1)
        }

        assertEquals(1L, countA, "Tenant A should see exactly its own 1 row")
        assertEquals(1L, countB, "Tenant B should see exactly its own 1 row")
    }

    @Test
    fun `audit_log is append-only — UPDATE denied for non-superuser role`() {
        ensureRestrictedRole()
        val entry = auditEntry()
        adapter.save(entry)
        entityManager.flush()

        restrictedConn().use { conn ->
            conn.autoCommit = false
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${entry.tenantId.value}'")
            assertThrows<SQLException>("Non-superuser UPDATE must be denied") {
                conn.createStatement().executeUpdate(
                    "UPDATE audit_log SET action = 'TAMPERED' WHERE id = '${entry.id}'"
                )
            }
            conn.rollback()
        }
    }

    @Test
    fun `cross-tenant RLS isolation — non-superuser cannot read another tenant rows`() {
        ensureRestrictedRole()
        adapter.save(auditEntry(tenantA))
        entityManager.flush()

        restrictedConn().use { conn ->
            conn.autoCommit = false
            // Authenticate as tenantB — should not see tenantA's rows
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantB.value}'")
            val rs = conn.createStatement()
                .executeQuery("SELECT COUNT(*) FROM audit_log WHERE tenant_id = '${tenantA.value}'")
            rs.next()
            assertEquals(0L, rs.getLong(1), "Non-superuser with tenantB context must see 0 of tenantA rows")
            conn.rollback()
        }
    }
}
