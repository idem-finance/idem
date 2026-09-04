package finance.idem.infrastructure.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
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
 * [finance.idem.infrastructure.persistence.setRlsTenantId] applies per call, transaction-scoped
 * (`SET LOCAL ROLE idem_app` + `SET LOCAL app.tenant_id`), on every repository adapter method.
 * Superusers bypass RLS regardless of FORCE, which is exactly the gap idem#286 tracked and this
 * test class closes.
 *
 * Covers `accounts` (simplest schema, single `tenant_isolation` policy covering
 * SELECT/INSERT/UPDATE/DELETE) plus `transactions`/`journal_lines` (idem#275 -- the issue
 * explicitly calls out transactions, not just accounts, as an isolation target).
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

    /** Inserts an account, a transaction, and one journal line, all under [tenantId]. Returns (accountId, transactionId). */
    private fun insertTransaction(tenantId: String): Pair<String, String> {
        val accountId = UUID.randomUUID().toString()
        val transactionId = UUID.randomUUID().toString()
        asIdemApp { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '$tenantId'")
            conn
                .prepareStatement(
                    "INSERT INTO accounts (id, tenant_id, name, currency, type, created_by) " +
                        "VALUES (?::uuid, ?::uuid, 'test account', 'USD', 'ASSET', 'test')",
                ).use {
                    it.setString(1, accountId)
                    it.setString(2, tenantId)
                    it.executeUpdate()
                }
            conn
                .prepareStatement(
                    "INSERT INTO transactions (id, tenant_id, idempotency_key, status, occurred_at, created_by) " +
                        "VALUES (?::uuid, ?::uuid, ?, 'COMMITTED', now(), 'test')",
                ).use {
                    it.setString(1, transactionId)
                    it.setString(2, tenantId)
                    it.setString(3, "idem-key-$transactionId")
                    it.executeUpdate()
                }
            conn
                .prepareStatement(
                    "INSERT INTO journal_lines " +
                        "(transaction_id, account_id, tenant_id, entry_type, amount, currency, " +
                        "monetary_entry_type, monetary_entry_data, created_by) " +
                        "VALUES (?::uuid, ?::uuid, ?::uuid, 'DEBIT', 100.00, 'USD', 'FIAT', '{}'::jsonb, 'test')",
                ).use {
                    it.setString(1, transactionId)
                    it.setString(2, accountId)
                    it.setString(3, tenantId)
                    it.executeUpdate()
                }
            conn.commit()
        }
        return accountId to transactionId
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

    // ── transactions (idem#275) ─────────────────────────────────────────────────

    @Test
    fun `transactions is empty for an idem_app connection with no app_tenant_id set`() {
        flyway().migrate()

        val tenantA = "f0000000-0000-0000-0000-000000000007"
        insertTransaction(tenantA)

        asIdemApp { conn ->
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM transactions WHERE tenant_id = '$tenantA'::uuid")
            rs.next()
            assertEquals(0, rs.getInt(1), "FORCE RLS with no app.tenant_id set must hide the transaction row entirely, not error")
        }
    }

    @Test
    fun `tenant A's session cannot read tenant B's transaction even with tenant_id = B in the WHERE clause`() {
        flyway().migrate()

        val tenantA = "f0000000-0000-0000-0000-000000000008"
        val tenantB = "f0000000-0000-0000-0000-000000000009"
        insertTransaction(tenantB)

        asIdemApp { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '$tenantA'")

            // Crafted query: explicitly filters for tenant B's rows -- the literal
            // "raw SQL injection attempt still respects RLS" scenario from idem#275.
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM transactions WHERE tenant_id = '$tenantB'::uuid")
            rs.next()
            assertEquals(0, rs.getInt(1), "Tenant A's session must not see tenant B's transaction via a crafted WHERE clause")
        }
    }

    @Test
    fun `inserting a transaction with a tenant_id that doesn't match the session's app_tenant_id is rejected`() {
        flyway().migrate()

        val sessionTenant = "f0000000-0000-0000-0000-000000000010"
        val otherTenant = "f0000000-0000-0000-0000-000000000011"

        asIdemApp { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '$sessionTenant'")

            assertFailsWith<SQLException>("WITH CHECK must reject a mismatched tenant_id") {
                conn
                    .prepareStatement(
                        "INSERT INTO transactions (tenant_id, idempotency_key, status, occurred_at, created_by) " +
                            "VALUES (?::uuid, 'mismatched-tx', 'COMMITTED', now(), 'test')",
                    ).use {
                        it.setString(1, otherTenant)
                        it.executeUpdate()
                    }
            }
        }
    }

    @Test
    fun `a matching tenant_id transaction insert succeeds and is readable back in the same session`() {
        flyway().migrate()

        val tenantId = "f0000000-0000-0000-0000-000000000012"
        insertTransaction(tenantId)

        asIdemApp { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '$tenantId'")
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM transactions WHERE tenant_id = '$tenantId'::uuid")
            rs.next()
            assertEquals(1, rs.getInt(1), "idem_app must still be able to do its normal, correctly-scoped job")
        }
    }

    // ── journal_lines (idem#275) ────────────────────────────────────────────────

    @Test
    fun `journal_lines is empty for an idem_app connection with no app_tenant_id set`() {
        flyway().migrate()

        val tenantA = "f0000000-0000-0000-0000-000000000013"
        insertTransaction(tenantA)

        asIdemApp { conn ->
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM journal_lines WHERE tenant_id = '$tenantA'::uuid")
            rs.next()
            assertEquals(0, rs.getInt(1), "FORCE RLS with no app.tenant_id set must hide the journal_lines row entirely, not error")
        }
    }

    @Test
    fun `tenant A's session cannot read tenant B's journal_lines even with tenant_id = B in the WHERE clause`() {
        flyway().migrate()

        val tenantA = "f0000000-0000-0000-0000-000000000014"
        val tenantB = "f0000000-0000-0000-0000-000000000015"
        insertTransaction(tenantB)

        asIdemApp { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '$tenantA'")

            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM journal_lines WHERE tenant_id = '$tenantB'::uuid")
            rs.next()
            assertEquals(0, rs.getInt(1), "Tenant A's session must not see tenant B's journal_lines via a crafted WHERE clause")
        }
    }

    @Test
    fun `inserting a journal_line with a tenant_id that doesn't match the session's app_tenant_id is rejected`() {
        flyway().migrate()

        val sessionTenant = "f0000000-0000-0000-0000-000000000016"
        val otherTenant = "f0000000-0000-0000-0000-000000000017"
        // Seed the transaction/account under otherTenant first so the composite FK is satisfied
        // -- this isolates the failure to RLS's WITH CHECK, not an unrelated FK violation.
        val (otherAccountId, otherTransactionId) = insertTransaction(otherTenant)

        asIdemApp { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '$sessionTenant'")

            assertFailsWith<SQLException>("WITH CHECK must reject a mismatched tenant_id even when the FK target exists") {
                conn
                    .prepareStatement(
                        "INSERT INTO journal_lines " +
                            "(transaction_id, account_id, tenant_id, entry_type, amount, currency, " +
                            "monetary_entry_type, monetary_entry_data, created_by) " +
                            "VALUES (?::uuid, ?::uuid, ?::uuid, 'CREDIT', 100.00, 'USD', 'FIAT', '{}'::jsonb, 'test')",
                    ).use {
                        it.setString(1, otherTransactionId)
                        it.setString(2, otherAccountId)
                        it.setString(3, otherTenant)
                        it.executeUpdate()
                    }
            }
        }
    }

    @Test
    fun `a matching tenant_id journal_line insert succeeds and is readable back in the same session`() {
        flyway().migrate()

        val tenantId = "f0000000-0000-0000-0000-000000000018"
        insertTransaction(tenantId)

        asIdemApp { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '$tenantId'")
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM journal_lines WHERE tenant_id = '$tenantId'::uuid")
            rs.next()
            assertEquals(1, rs.getInt(1), "idem_app must still be able to do its normal, correctly-scoped job")
        }
    }
}
