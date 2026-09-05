package finance.idem.infrastructure.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
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
 * explicitly calls out transactions, not just accounts, as an isolation target). The four-shape
 * pattern (empty-with-no-tenant-set / crafted-WHERE-blocked / mismatched-insert-rejected /
 * matching-insert-succeeds) is identical across all three tables, so it is parameterized over
 * [RlsCase] rather than repeated per table -- `journal_lines`' mismatched-insert case is the one
 * table that needs a pre-existing transaction/account for its composite FK, which is why that
 * assertion is owned by the case itself rather than assumed to be interchangeable across tables.
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

        @JvmStatic
        fun rlsCases(): List<RlsCase> =
            listOf(
                RlsCase(
                    tableName = "accounts",
                    seed = ::insertAccount,
                    assertMismatchedInsertRejected = { sessionTenant, otherTenant ->
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
                    },
                ),
                RlsCase(
                    tableName = "transactions",
                    seed = { insertTransaction(it) },
                    assertMismatchedInsertRejected = { sessionTenant, otherTenant ->
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
                    },
                ),
                RlsCase(
                    tableName = "journal_lines",
                    seed = { insertTransaction(it) },
                    assertMismatchedInsertRejected = { sessionTenant, otherTenant ->
                        // Seed the transaction/account under otherTenant first so the composite FK
                        // is satisfied -- this isolates the failure to RLS's WITH CHECK, not an
                        // unrelated FK violation.
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
                    },
                ),
            )

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
    }

    /**
     * One table's instance of the four-shape RLS proof: how to seed a row under a tenant, and
     * how to prove a cross-tenant INSERT is rejected (which, for `journal_lines`, also needs to
     * seed a valid FK target under the mismatched tenant first).
     */
    class RlsCase(
        val tableName: String,
        val seed: (tenantId: String) -> Unit,
        val assertMismatchedInsertRejected: (sessionTenant: String, otherTenant: String) -> Unit,
    ) {
        override fun toString() = tableName
    }

    private fun flyway(): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()

    @ParameterizedTest(name = "{0}")
    @MethodSource("rlsCases")
    fun `table is empty for an idem_app connection with no app_tenant_id set`(case: RlsCase) {
        flyway().migrate()

        val tenantA = UUID.randomUUID().toString()
        case.seed(tenantA)

        // No SET LOCAL app.tenant_id on this connection at all.
        asIdemApp { conn ->
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM ${case.tableName} WHERE tenant_id = '$tenantA'::uuid")
            rs.next()
            assertEquals(0, rs.getInt(1), "FORCE RLS with no app.tenant_id set must hide the ${case.tableName} row entirely, not error")
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rlsCases")
    fun `tenant A's session cannot read tenant B's row even with tenant_id = B in the WHERE clause`(case: RlsCase) {
        flyway().migrate()

        val tenantA = UUID.randomUUID().toString()
        val tenantB = UUID.randomUUID().toString()
        case.seed(tenantB)

        asIdemApp { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '$tenantA'")

            // Crafted query: explicitly filters for tenant B's rows. RLS must still apply
            // on top of this filter, using the session's app.tenant_id, not the query's own
            // WHERE clause -- this is the literal "crafted query" scenario from idem#270.
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM ${case.tableName} WHERE tenant_id = '$tenantB'::uuid")
            rs.next()
            assertEquals(0, rs.getInt(1), "Tenant A's session must not see tenant B's ${case.tableName} row via a crafted WHERE clause")
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rlsCases")
    fun `inserting a row with a tenant_id that doesn't match the session's app_tenant_id is rejected`(case: RlsCase) {
        flyway().migrate()

        val sessionTenant = UUID.randomUUID().toString()
        val otherTenant = UUID.randomUUID().toString()

        case.assertMismatchedInsertRejected(sessionTenant, otherTenant)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rlsCases")
    fun `a matching tenant_id insert succeeds and is readable back in the same session`(case: RlsCase) {
        flyway().migrate()

        val tenantId = UUID.randomUUID().toString()
        case.seed(tenantId)

        asIdemApp { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '$tenantId'")
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM ${case.tableName} WHERE tenant_id = '$tenantId'::uuid")
            rs.next()
            assertEquals(1, rs.getInt(1), "idem_app must still be able to do its normal, correctly-scoped job")
        }
    }
}
