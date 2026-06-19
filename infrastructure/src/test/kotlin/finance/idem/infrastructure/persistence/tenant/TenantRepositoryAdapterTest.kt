package finance.idem.infrastructure.persistence.tenant

import finance.idem.application.tenant.TenantWebhookConfig
import finance.idem.core.TenantId
import jakarta.persistence.EntityManager
import org.hibernate.Session
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
import kotlin.test.assertEquals
import kotlin.test.assertNull

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(TenantRepositoryAdapter::class)
class TenantRepositoryAdapterTest {

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
    lateinit var adapter: TenantRepositoryAdapter

    @Autowired
    lateinit var entityManager: EntityManager

    private val tenantA = TenantId.generate()
    private val tenantB = TenantId.generate()

    private fun insertTenant(tenantId: TenantId, webhookUrl: String?, webhookSecret: String?) {
        val session = entityManager.unwrap(Session::class.java)
        session.doWork { conn ->
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${tenantId.value}'")
            conn.prepareStatement(
                "INSERT INTO tenants (id, webhook_url, webhook_secret) VALUES (?::uuid, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, tenantId.value.toString())
                stmt.setString(2, webhookUrl)
                stmt.setString(3, webhookSecret)
                stmt.executeUpdate()
            }
        }
        entityManager.clear()
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
}
