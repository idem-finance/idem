package finance.idem.infrastructure.persistence.idempotency

import finance.idem.core.TenantId
import finance.idem.infrastructure.SharedPostgresTestBase
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresSettlementIdempotencyStore::class)
class PostgresSettlementIdempotencyStoreTest : SharedPostgresTestBase() {
    @Autowired
    lateinit var store: PostgresSettlementIdempotencyStore

    @Autowired
    lateinit var entityManager: EntityManager

    private val tenantA = TenantId.generate()
    private val tenantB = TenantId.generate()

    @Test
    fun `find returns null when key does not exist`() {
        assertNull(store.find("missing-key", tenantA))
    }

    @Test
    fun `tryRecord returns true and find retrieves the settlement id`() {
        val settlementId = UUID.randomUUID()
        val claimed = store.tryRecord("key-001", tenantA, settlementId)

        assertTrue(claimed)
        val found = store.find("key-001", tenantA)
        assertNotNull(found)
        assertEquals(settlementId, found)
    }

    @Test
    fun `tryRecord returns false on duplicate key for same tenant`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()

        assertTrue(store.tryRecord("key-002", tenantA, first))
        assertFalse(store.tryRecord("key-002", tenantA, second))

        assertEquals(first, store.find("key-002", tenantA))
    }

    @Test
    fun `same key for different tenants are independent`() {
        val settlementA = UUID.randomUUID()
        val settlementB = UUID.randomUUID()

        assertTrue(store.tryRecord("shared-key", tenantA, settlementA))
        assertTrue(store.tryRecord("shared-key", tenantB, settlementB))

        assertEquals(settlementA, store.find("shared-key", tenantA))
        assertEquals(settlementB, store.find("shared-key", tenantB))
    }

    @Test
    fun `find returns null for other tenant's key (RLS isolation)`() {
        val settlementId = UUID.randomUUID()
        store.tryRecord("key-rls", tenantA, settlementId)

        assertNull(store.find("key-rls", tenantB))
    }

    @Test
    fun `find returns null when key is expired`() {
        val settlementId = UUID.randomUUID()
        entityManager.createNativeQuery("SET LOCAL app.tenant_id = '${tenantA.value}'").executeUpdate()
        entityManager
            .createNativeQuery(
                """
            INSERT INTO settlement_idempotency_keys (tenant_id, key, settlement_id, expires_at)
            VALUES (CAST(:tenantId AS uuid), :key, CAST(:settlementId AS uuid), now() - interval '1 second')
        """,
            ).setParameter("tenantId", tenantA.value.toString())
            .setParameter("key", "expired-key")
            .setParameter("settlementId", settlementId.toString())
            .executeUpdate()

        assertNull(store.find("expired-key", tenantA))
    }
}
