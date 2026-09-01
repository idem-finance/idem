package finance.idem.infrastructure.persistence

import finance.idem.core.StablecoinToken
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Testcontainers
class FlywayMigrationTest {
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

    @Test
    fun `all 29 migrations apply cleanly`() {
        flyway().migrate()
        val applied = flyway().info().applied()
        assertEquals(29, applied.size)
        assertTrue(applied.none { it.state.isFailed() }, "No migration should be in failed state")
    }

    @Test
    fun `all expected tables exist after migration`() {
        flyway().migrate()

        val expectedTables =
            listOf(
                "accounts",
                "transactions",
                "journal_lines",
                "audit_log",
                "webhook_outbox",
                "chain_checkpoint",
                "idempotency_keys",
                "api_keys",
                "watched_addresses",
                "tenants",
                "shedlock",
                "installation_metadata",
                "workflow_plans",
                "workflow_steps",
                "agent_audit_events",
                "travel_rule_data",
                "compliance_queue",
                "lgpd_retention_schedule",
                "policy_rules",
                "settlement_idempotency_keys",
                "usage_metrics",
                "usage_metrics_hourly",
                "usage_metrics_rollup_state",
            )

        postgres.createConnection("").use { conn ->
            expectedTables.forEach { table ->
                val rs = conn.metaData.getTables(null, "public", table, arrayOf("TABLE"))
                assertTrue(rs.next(), "Table '$table' should exist after migration")
            }
        }
    }

    @Test
    fun `idempotency_keys has composite PK on tenant_id and key`() {
        flyway().migrate()

        postgres.createConnection("").use { conn ->
            conn.autoCommit = false
            val tenantId = "a0000000-0000-0000-0000-000000000001"
            val txId = "b0000000-0000-0000-0000-000000000001"

            // FORCE RLS requires app.tenant_id to be set for DML
            conn.createStatement().execute("SET LOCAL app.tenant_id = '$tenantId'")

            conn
                .prepareStatement(
                    "INSERT INTO idempotency_keys (tenant_id, key, transaction_id, expires_at) VALUES (?::UUID, ?, ?::UUID, now() + interval '24 hours')",
                ).use {
                    it.setString(1, tenantId)
                    it.setString(2, "key-001")
                    it.setString(3, txId)
                    it.executeUpdate()
                }

            assertFailsWith<SQLException>("Duplicate key should be rejected") {
                conn
                    .prepareStatement(
                        "INSERT INTO idempotency_keys (tenant_id, key, transaction_id, expires_at) VALUES (?::UUID, ?, ?::UUID, now() + interval '24 hours')",
                    ).use {
                        it.setString(1, tenantId)
                        it.setString(2, "key-001")
                        it.setString(3, txId)
                        it.executeUpdate()
                    }
            }

            conn.rollback()
        }
    }

    @Test
    fun `transactions unique constraint prevents duplicate idempotency key per tenant`() {
        flyway().migrate()

        postgres.createConnection("").use { conn ->
            conn.autoCommit = false
            val tenantId = "a0000000-0000-0000-0000-000000000002"

            // FORCE RLS requires app.tenant_id to be set for DML
            conn.createStatement().execute("SET LOCAL app.tenant_id = '$tenantId'")

            fun insertTx(key: String) =
                conn
                    .prepareStatement(
                        "INSERT INTO transactions (tenant_id, idempotency_key, status, occurred_at, created_at, created_by) VALUES (?::UUID, ?, 'PENDING', now(), now(), 'test')",
                    ).use {
                        it.setString(1, tenantId)
                        it.setString(2, key)
                        it.executeUpdate()
                    }

            insertTx("tx-key-001")

            assertFailsWith<SQLException>("Duplicate idempotency key should be rejected") {
                insertTx("tx-key-001")
            }

            conn.rollback()
        }
    }

    @Test
    fun `webhook_outbox is readable across tenants without app_tenant_id (NO FORCE RLS)`() {
        flyway().migrate()

        val tenantA = "c0000000-0000-0000-0000-000000000001"
        val tenantB = "c0000000-0000-0000-0000-000000000002"

        fun insertOutboxRow(tenantId: String) {
            postgres.createConnection("").use { conn ->
                conn.autoCommit = false
                conn.createStatement().execute("SET LOCAL app.tenant_id = '$tenantId'")
                conn
                    .prepareStatement(
                        "INSERT INTO webhook_outbox (id, tenant_id, transaction_id, event_type, payload) " +
                            "VALUES (gen_random_uuid(), ?::uuid, gen_random_uuid(), 'transaction.committed', '{}')",
                    ).use {
                        it.setString(1, tenantId)
                        it.executeUpdate()
                    }
                conn.commit()
            }
        }

        insertOutboxRow(tenantA)
        insertOutboxRow(tenantB)

        // No app.tenant_id set on this connection — NO FORCE RLS lets the owner role (idem)
        // see rows across tenants, which #55's WebhookOutboxPoller relies on for its
        // cross-tenant dispatchable batch query.
        postgres.createConnection("").use { conn ->
            val rs =
                conn.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM webhook_outbox WHERE tenant_id IN ('$tenantA'::uuid, '$tenantB'::uuid) AND status = 'PENDING'",
                )
            rs.next()
            assertEquals(2, rs.getInt(1), "Owner role should see PENDING rows across tenants without app.tenant_id set")
        }
    }

    @Test
    fun `travel_rule_data transfer_asset CHECK constraint matches StablecoinToken enum`() {
        flyway().migrate()

        val enumValues = StablecoinToken.values().map { it.name }.toSet()

        postgres.createConnection("").use { conn ->
            // pg_get_constraintdef returns e.g. "CHECK ((transfer_asset = ANY (ARRAY['USDC'::text, ...])))".
            // Extract the quoted token names from that string.
            val rs =
                conn
                    .prepareStatement(
                        """
                        SELECT pg_get_constraintdef(oid)
                        FROM pg_constraint
                        WHERE conrelid = 'travel_rule_data'::regclass
                          AND contype = 'c'
                          AND pg_get_constraintdef(oid) LIKE '%transfer_asset%'
                        """.trimIndent(),
                    ).executeQuery()
            assertTrue(rs.next(), "travel_rule_data should have a CHECK constraint on transfer_asset")
            val constraintDef = rs.getString(1)

            // Pull out every single-quoted literal from the constraint definition.
            val constraintValues =
                Regex("'([^']+)'")
                    .findAll(constraintDef)
                    .map { it.groupValues[1] }
                    .toSet()

            assertEquals(
                enumValues,
                constraintValues,
                "transfer_asset CHECK constraint must list exactly the same values as StablecoinToken",
            )
        }
    }

    @Test
    fun `tenants is readable across tenants without app_tenant_id (NO FORCE RLS)`() {
        flyway().migrate()

        val tenantA = "d0000000-0000-0000-0000-000000000001"
        val tenantB = "d0000000-0000-0000-0000-000000000002"

        fun insertTenant(tenantId: String) {
            postgres.createConnection("").use { conn ->
                conn.autoCommit = false
                conn.createStatement().execute("SET LOCAL app.tenant_id = '$tenantId'")
                conn
                    .prepareStatement(
                        "INSERT INTO tenants (id, webhook_url, webhook_secret) VALUES (?::uuid, 'https://example.com/webhook', 'secret')",
                    ).use {
                        it.setString(1, tenantId)
                        it.executeUpdate()
                    }
                conn.commit()
            }
        }

        insertTenant(tenantA)
        insertTenant(tenantB)

        // No app.tenant_id set on this connection — NO FORCE RLS lets the owner role (idem)
        // see rows across tenants, which #55's WebhookOutboxPoller relies on to resolve each
        // dispatch row's per-tenant webhook config.
        postgres.createConnection("").use { conn ->
            val rs =
                conn.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM tenants WHERE id IN ('$tenantA'::uuid, '$tenantB'::uuid)",
                )
            rs.next()
            assertEquals(2, rs.getInt(1), "Owner role should see tenant rows across tenants without app.tenant_id set")
        }
    }

    @Test
    fun `usage_metrics is readable across tenants without app_tenant_id (NO FORCE RLS)`() {
        flyway().migrate()

        val tenantA = "e0000000-0000-0000-0000-000000000001"
        val tenantB = "e0000000-0000-0000-0000-000000000002"

        fun insertUsageEvent(tenantId: String) {
            postgres.createConnection("").use { conn ->
                conn.autoCommit = false
                conn.createStatement().execute("SET LOCAL app.tenant_id = '$tenantId'")
                conn
                    .prepareStatement(
                        "INSERT INTO usage_metrics (tenant_id, metric_type, amount) VALUES (?::uuid, 'TRANSACTION_COUNT', 1)",
                    ).use {
                        it.setString(1, tenantId)
                        it.executeUpdate()
                    }
                conn.commit()
            }
        }

        insertUsageEvent(tenantA)
        insertUsageEvent(tenantB)

        // No app.tenant_id set on this connection — NO FORCE RLS lets the owner role (idem)
        // see rows across tenants, which the hourly rollup job relies on to aggregate in one
        // cross-tenant query.
        postgres.createConnection("").use { conn ->
            val rs =
                conn.createStatement().executeQuery(
                    "SELECT COUNT(*) FROM usage_metrics WHERE tenant_id IN ('$tenantA'::uuid, '$tenantB'::uuid)",
                )
            rs.next()
            assertEquals(2, rs.getInt(1), "Owner role should see usage_metrics rows across tenants without app.tenant_id set")
        }
    }

    // Note: no test here verifies FORCE RLS actually hides another tenant's
    // usage_metrics_hourly row -- the Testcontainers postgres user is a superuser, and
    // superusers bypass RLS regardless of FORCE (only non-superuser table owners are subject
    // to it). No other FORCE RLS table in this file (e.g. agent_audit_events) has such a test
    // for the same reason; the guarantee itself relies on the app's runtime DB role not being
    // a superuser, which is outside what this test harness can exercise.

    @Test
    fun `usage_metrics_hourly has an INSERT policy allowing the rollup job's cross-tenant write`() {
        flyway().migrate()

        // Can't test RLS *enforcement* here for the same superuser reason as above -- assert
        // the policy exists instead, which is the actual gap V31 closes (rollupHour's
        // cross-tenant INSERT had no policy to execute under at all).
        postgres.createConnection("").use { conn ->
            val rs =
                conn.createStatement().executeQuery(
                    "SELECT policyname FROM pg_policies WHERE schemaname = 'public' " +
                        "AND tablename = 'usage_metrics_hourly' AND cmd = 'INSERT'",
                )
            assertTrue(rs.next(), "usage_metrics_hourly should have an INSERT policy")
            assertEquals("rollup_insert", rs.getString("policyname"))
        }
    }

    @Test
    fun `usage_metrics has a partial unique index on tenant_id, metric_type, idempotency_key`() {
        flyway().migrate()

        postgres.createConnection("").use { conn ->
            val rs =
                conn.createStatement().executeQuery(
                    "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' " +
                        "AND tablename = 'usage_metrics' AND indexname = 'uq_usage_metrics_idempotency'",
                )
            assertTrue(rs.next(), "usage_metrics should have the uq_usage_metrics_idempotency partial unique index")
        }
    }
}
