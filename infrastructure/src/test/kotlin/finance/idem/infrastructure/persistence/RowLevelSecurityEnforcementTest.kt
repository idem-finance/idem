package finance.idem.infrastructure.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Proves FORCE RLS actually blocks cross-tenant access -- not just that the policies exist
 * (FlywayMigrationTest covers that). Postgres refuses to ever strip SUPERUSER from the
 * bootstrap role this container's default connection authenticates as (`ALTER ROLE idem
 * NOSUPERUSER` fails with "the bootstrap user must have the SUPERUSER attribute"), so V31
 * creates a separate NOLOGIN idem_app role instead and grants the bootstrap role membership
 * in it. Every assertion here runs `SET ROLE idem_app` first, on a connection still
 * authenticated as the bootstrap role -- the same de-escalation
 * spring.datasource.hikari.connection-init-sql applies automatically to every connection the
 * app's own pool opens (application.yaml). Superusers bypass RLS regardless of FORCE, which
 * is exactly the gap idem#286 tracked and this test class closes.
 *
 * Uses `accounts` as the representative FORCE RLS table (simplest schema, single
 * `tenant_isolation` policy covering SELECT/INSERT/UPDATE/DELETE).
 */
@Testcontainers
class RowLevelSecurityEnforcementTest {
    companion object {
        @Container
        val postgres =
            PostgreSQLContainer("postgres:16")
                .withDatabaseName("idem_test")
                .withUsername("idem")
                .withPassword("idem")
    }

    private fun flyway(): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()

    /** Opens a connection, starts a transaction, and assumes idem_app for the rest of it. */
    private fun asIdemApp(block: (Connection) -> Unit) {
        postgres.createConnection("").use { conn ->
            conn.autoCommit = false
            conn.createStatement().execute("SET LOCAL ROLE idem_app")
            block(conn)
        }
    }

    private fun insertAccount(tenantId: String) {
        asIdemApp { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '$tenantId'")
            conn
                .prepareStatement(
                    "INSERT INTO accounts (tenant_id, name, currency, type, created_by) " +
                        "VALUES (?::uuid, 'test account', 'USD', 'ASSET', 'test')",
                ).use {
                    it.setString(1, tenantId)
                    it.executeUpdate()
                }
            conn.commit()
        }
    }

    @Test
    fun `accounts is empty for an idem_app connection with no app_tenant_id set`() {
        flyway().migrate()

        val tenantA = "f0000000-0000-0000-0000-000000000001"
        insertAccount(tenantA)

        // No SET LOCAL app.tenant_id on this connection at all.
        asIdemApp { conn ->
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM accounts WHERE tenant_id = '$tenantA'::uuid")
            rs.next()
            assertEquals(0, rs.getInt(1), "FORCE RLS with no app.tenant_id set must hide the row entirely, not error")
        }
    }

    @Test
    fun `tenant A's session cannot read tenant B's account even with tenant_id = B in the WHERE clause`() {
        flyway().migrate()

        val tenantA = "f0000000-0000-0000-0000-000000000002"
        val tenantB = "f0000000-0000-0000-0000-000000000003"
        insertAccount(tenantB)

        asIdemApp { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '$tenantA'")

            // Crafted query: explicitly filters for tenant B's rows. RLS must still apply
            // on top of this filter, using the session's app.tenant_id, not the query's own
            // WHERE clause -- this is the literal "crafted query" scenario from idem#270.
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM accounts WHERE tenant_id = '$tenantB'::uuid")
            rs.next()
            assertEquals(0, rs.getInt(1), "Tenant A's session must not see tenant B's row via a crafted WHERE clause")
        }
    }

    @Test
    fun `inserting a row with a tenant_id that doesn't match the session's app_tenant_id is rejected`() {
        flyway().migrate()

        val sessionTenant = "f0000000-0000-0000-0000-000000000004"
        val otherTenant = "f0000000-0000-0000-0000-000000000005"

        asIdemApp { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '$sessionTenant'")

            assertFailsWith<SQLException>("WITH CHECK must reject a mismatched tenant_id") {
                conn
                    .prepareStatement(
                        "INSERT INTO accounts (tenant_id, name, currency, type, created_by) " +
                            "VALUES (?::uuid, 'test account', 'USD', 'ASSET', 'test')",
                    ).use {
                        it.setString(1, otherTenant)
                        it.executeUpdate()
                    }
            }
        }
    }

    @Test
    fun `a matching tenant_id insert succeeds and is readable back in the same session`() {
        flyway().migrate()

        val tenantId = "f0000000-0000-0000-0000-000000000006"
        insertAccount(tenantId)

        asIdemApp { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '$tenantId'")
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM accounts WHERE tenant_id = '$tenantId'::uuid")
            rs.next()
            assertEquals(1, rs.getInt(1), "idem_app must still be able to do its normal, correctly-scoped job")
        }
    }
}
