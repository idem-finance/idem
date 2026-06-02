package finance.idem.infrastructure.persistence.idempotency

import finance.idem.core.TenantId
import finance.idem.core.TransactionId
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(PostgresIdempotencyStore::class)
class PostgresIdempotencyStoreTest {

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
    lateinit var store: PostgresIdempotencyStore

    @Autowired
    lateinit var entityManager: EntityManager

    private val tenantA = TenantId.generate()
    private val tenantB = TenantId.generate()

    @Test
    fun `find returns null when key does not exist`() {
        assertNull(store.find("missing-key", tenantA))
    }

    @Test
    fun `tryRecord returns true and find retrieves the transaction id`() {
        val txId = TransactionId.generate()
        val claimed = store.tryRecord("key-001", tenantA, txId)

        assertTrue(claimed)
        val found = store.find("key-001", tenantA)
        assertNotNull(found)
        assertEquals(txId, found)
    }

    @Test
    fun `tryRecord returns false on duplicate key for same tenant`() {
        val first = TransactionId.generate()
        val second = TransactionId.generate()

        assertTrue(store.tryRecord("key-002", tenantA, first))
        assertFalse(store.tryRecord("key-002", tenantA, second))

        // find still returns the original transaction id
        assertEquals(first, store.find("key-002", tenantA))
    }

    @Test
    fun `same key for different tenants are independent`() {
        val txA = TransactionId.generate()
        val txB = TransactionId.generate()

        assertTrue(store.tryRecord("shared-key", tenantA, txA))
        assertTrue(store.tryRecord("shared-key", tenantB, txB))

        assertEquals(txA, store.find("shared-key", tenantA))
        assertEquals(txB, store.find("shared-key", tenantB))
    }

    @Test
    fun `find returns null for other tenant's key (RLS isolation)`() {
        val txId = TransactionId.generate()
        store.tryRecord("key-rls", tenantA, txId)

        assertNull(store.find("key-rls", tenantB))
    }

    @Test
    fun `release removes the key so tryRecord succeeds again`() {
        val original = TransactionId.generate()
        store.tryRecord("key-release", tenantA, original)
        assertFalse(store.tryRecord("key-release", tenantA, TransactionId.generate()))

        store.release("key-release", tenantA)

        val replacement = TransactionId.generate()
        assertTrue(store.tryRecord("key-release", tenantA, replacement))
        assertEquals(replacement, store.find("key-release", tenantA))
    }

    @Test
    fun `find returns null when key is expired`() {
        val txId = TransactionId.generate()
        entityManager.createNativeQuery("SET LOCAL app.tenant_id = '${tenantA.value}'").executeUpdate()
        entityManager.createNativeQuery("""
            INSERT INTO idempotency_keys (tenant_id, key, transaction_id, expires_at)
            VALUES (CAST(:tenantId AS uuid), :key, CAST(:txId AS uuid), now() - interval '1 second')
        """)
            .setParameter("tenantId", tenantA.value.toString())
            .setParameter("key", "expired-key")
            .setParameter("txId", txId.value.toString())
            .executeUpdate()

        assertNull(store.find("expired-key", tenantA))
    }
}
