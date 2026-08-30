package finance.idem.infrastructure.persistence.tenant

import finance.idem.application.tenant.TenantWebhookConfig
import finance.idem.core.TenantId
import finance.idem.infrastructure.SharedPostgresTestBase
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TenantRepositoryAdapter::class)
class TenantRepositoryAdapterTest : SharedPostgresTestBase() {
    @Autowired
    lateinit var adapter: TenantRepositoryAdapter

    @Autowired
    lateinit var entityManager: EntityManager

    private val tenantA = TenantId.generate()
    private val tenantB = TenantId.generate()

    private fun insertTenant(
        tenantId: TenantId,
        webhookUrl: String?,
        webhookSecret: String?,
    ) {
        val session = entityManager.unwrap(Session::class.java)
        session.doWork { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantId.value}'")
            conn
                .prepareStatement(
                    "INSERT INTO tenants (id, webhook_url, webhook_secret) VALUES (?::uuid, ?, ?)",
                ).use { stmt ->
                    stmt.setString(1, tenantId.value.toString())
                    stmt.setString(2, webhookUrl)
                    stmt.setString(3, webhookSecret)
                    stmt.executeUpdate()
                }
        }
        entityManager.clear()
    }

    private data class TenantConfigRow(
        val plan: String,
        val rateLimitPerSecond: Int?,
        val rateLimitPerMinute: Int?,
        val featureFlags: String,
        val hmacKey: String?,
        val billingCustomerId: String?,
        val suspendedAt: Instant?,
    )

    private fun insertTenantWithFullConfig(
        tenantId: TenantId,
        webhookUrl: String?,
        webhookSecret: String?,
        row: TenantConfigRow,
    ) {
        val session = entityManager.unwrap(Session::class.java)
        session.doWork { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantId.value}'")
            conn
                .prepareStatement(
                    """
                    INSERT INTO tenants (
                        id, webhook_url, webhook_secret, plan, rate_limit_per_second,
                        rate_limit_per_minute, feature_flags, hmac_key, billing_customer_id, suspended_at
                    ) VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, tenantId.value.toString())
                    stmt.setString(2, webhookUrl)
                    stmt.setString(3, webhookSecret)
                    stmt.setString(4, row.plan)
                    stmt.setObject(5, row.rateLimitPerSecond)
                    stmt.setObject(6, row.rateLimitPerMinute)
                    stmt.setString(7, row.featureFlags)
                    stmt.setString(8, row.hmacKey)
                    stmt.setString(9, row.billingCustomerId)
                    stmt.setObject(10, row.suspendedAt?.let(java.sql.Timestamp::from))
                    stmt.executeUpdate()
                }
        }
        entityManager.clear()
    }

    private fun readTenantConfigRow(tenantId: TenantId): TenantConfigRow {
        val session = entityManager.unwrap(Session::class.java)
        var row: TenantConfigRow? = null
        session.doWork { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantId.value}'")
            conn
                .prepareStatement(
                    """
                    SELECT plan, rate_limit_per_second, rate_limit_per_minute, feature_flags,
                           hmac_key, billing_customer_id, suspended_at
                    FROM tenants WHERE id = ?::uuid
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setString(1, tenantId.value.toString())
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        row =
                            TenantConfigRow(
                                plan = rs.getString("plan"),
                                rateLimitPerSecond = rs.getObject("rate_limit_per_second") as Int?,
                                rateLimitPerMinute = rs.getObject("rate_limit_per_minute") as Int?,
                                featureFlags = rs.getString("feature_flags"),
                                hmacKey = rs.getString("hmac_key"),
                                billingCustomerId = rs.getString("billing_customer_id"),
                                suspendedAt = rs.getTimestamp("suspended_at")?.toInstant(),
                            )
                    }
                }
        }
        return row!!
    }

    @Test
    fun `findWebhookConfig returns config when both url and secret are set`() {
        insertTenant(tenantA, "https://a.example.com/webhook", "secret-a")

        val config = adapter.findWebhookConfig(tenantA)

        assertEquals(TenantWebhookConfig("https://a.example.com/webhook", "secret-a"), config)
    }

    @Test
    fun `findWebhookConfig returns null when no row exists for the tenant`() {
        val config = adapter.findWebhookConfig(tenantA)

        assertNull(config)
    }

    @Test
    fun `findWebhookConfig returns null when webhook_url and webhook_secret are not set`() {
        insertTenant(tenantA, webhookUrl = null, webhookSecret = null)

        val config = adapter.findWebhookConfig(tenantA)

        assertNull(config)
    }

    @Test
    fun `findWebhookConfig resolves a tenant's config regardless of app_tenant_id (NO FORCE RLS)`() {
        insertTenant(tenantA, "https://a.example.com/webhook", "secret-a")
        // Last SET LOCAL in this transaction leaves app.tenant_id = tenantB
        insertTenant(tenantB, "https://b.example.com/webhook", "secret-b")

        val configA = adapter.findWebhookConfig(tenantA)

        assertEquals(TenantWebhookConfig("https://a.example.com/webhook", "secret-a"), configA)
    }

    @Test
    fun `upsertWebhookConfig inserts a new tenant row when none exists`() {
        val newTenant = TenantId.generate()
        val config = TenantWebhookConfig("https://new.example.com/hook", "secret-new")

        adapter.upsertWebhookConfig(newTenant, config)

        assertEquals(config, adapter.findWebhookConfig(newTenant))
    }

    @Test
    fun `upsertWebhookConfig updates webhook on existing tenant row`() {
        insertTenant(tenantA, "https://old.example.com/hook", "old-secret")

        val updated = TenantWebhookConfig("https://new.example.com/hook", "new-secret")
        adapter.upsertWebhookConfig(tenantA, updated)

        assertEquals(updated, adapter.findWebhookConfig(tenantA))
    }

    @Test
    fun `upsertWebhookConfig isolates updates to the target tenant`() {
        insertTenant(tenantA, "https://a.example.com/hook", "secret-a")
        insertTenant(tenantB, "https://b.example.com/hook", "secret-b")

        adapter.upsertWebhookConfig(tenantA, TenantWebhookConfig("https://a-new.example.com/hook", "secret-a-new"))

        // tenantB untouched
        assertEquals(
            TenantWebhookConfig("https://b.example.com/hook", "secret-b"),
            adapter.findWebhookConfig(tenantB),
        )
    }

    @Test
    fun `upsertWebhookConfig preserves plan, limits, flags, hmacKey, billing and suspension state`() {
        val suspendedAt = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS)
        insertTenantWithFullConfig(
            tenantA,
            webhookUrl = "https://old.example.com/hook",
            webhookSecret = "old-secret",
            row =
                TenantConfigRow(
                    plan = "CLOUD",
                    rateLimitPerSecond = 50,
                    rateLimitPerMinute = 1000,
                    featureFlags = "flag-a,flag-b",
                    hmacKey = "tenant-a-hmac-key",
                    billingCustomerId = "cus_123",
                    suspendedAt = suspendedAt,
                ),
        )

        val updatedWebhook = TenantWebhookConfig("https://new.example.com/hook", "new-secret")
        adapter.upsertWebhookConfig(tenantA, updatedWebhook)

        assertEquals(updatedWebhook, adapter.findWebhookConfig(tenantA))
        val row = readTenantConfigRow(tenantA)
        assertEquals("CLOUD", row.plan)
        assertEquals(50, row.rateLimitPerSecond)
        assertEquals(1000, row.rateLimitPerMinute)
        assertEquals("flag-a,flag-b", row.featureFlags)
        assertEquals("tenant-a-hmac-key", row.hmacKey)
        assertEquals("cus_123", row.billingCustomerId)
        assertEquals(suspendedAt, row.suspendedAt)
    }
}
