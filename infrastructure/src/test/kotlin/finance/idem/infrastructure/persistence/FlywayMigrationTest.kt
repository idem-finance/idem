package finance.idem.infrastructure.persistence

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
        val postgres = PostgreSQLContainer("postgres:16")
            .withDatabaseName("idem_test")
            .withUsername("idem")
            .withPassword("idem")
    }

    private fun flyway(): Flyway = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("classpath:db/migration")
        .load()

    @Test
    fun `all 9 migrations apply cleanly`() {
        flyway().migrate()
        val applied = flyway().info().applied()
        assertEquals(9, applied.size)
        assertTrue(applied.none { it.state.isFailed() }, "No migration should be in failed state")
    }

    @Test
    fun `all expected tables exist after migration`() {
        flyway().migrate()

        val expectedTables = listOf(
            "accounts", "transactions", "journal_lines",
            "audit_log", "webhook_outbox", "chain_checkpoint", "idempotency_keys", "api_keys",
            "watched_addresses",
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
            val txId     = "b0000000-0000-0000-0000-000000000001"

            // FORCE RLS requires app.tenant_id to be set for DML
            conn.createStatement().execute("SET LOCAL app.tenant_id = '$tenantId'")

            conn.prepareStatement(
                "INSERT INTO idempotency_keys (tenant_id, key, transaction_id, expires_at) VALUES (?::UUID, ?, ?::UUID, now() + interval '24 hours')"
            ).use { it.setString(1, tenantId); it.setString(2, "key-001"); it.setString(3, txId); it.executeUpdate() }

            assertFailsWith<SQLException>("Duplicate key should be rejected") {
                conn.prepareStatement(
                    "INSERT INTO idempotency_keys (tenant_id, key, transaction_id, expires_at) VALUES (?::UUID, ?, ?::UUID, now() + interval '24 hours')"
                ).use { it.setString(1, tenantId); it.setString(2, "key-001"); it.setString(3, txId); it.executeUpdate() }
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

            fun insertTx(key: String) = conn.prepareStatement(
                "INSERT INTO transactions (tenant_id, idempotency_key, status, occurred_at, created_at, created_by) VALUES (?::UUID, ?, 'PENDING', now(), now(), 'test')"
            ).use { it.setString(1, tenantId); it.setString(2, key); it.executeUpdate() }

            insertTx("tx-key-001")

            assertFailsWith<SQLException>("Duplicate idempotency key should be rejected") {
                insertTx("tx-key-001")
            }

            conn.rollback()
        }
    }
}
