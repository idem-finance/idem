package finance.idem.infrastructure.persistence

import finance.idem.core.AccountId
import finance.idem.core.FiatCurrency
import finance.idem.core.TenantId
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(AccountRepositoryAdapter::class)
class AccountRepositoryAdapterTest {
    companion object {
        @Container
        val postgres =
            PostgreSQLContainer("postgres:16")
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

    @Autowired lateinit var adapter: AccountRepositoryAdapter

    private val tenantA = TenantId.generate()
    private val tenantB = TenantId.generate()
    private val now = Instant.now()

    private fun account(
        id: AccountId = AccountId.generate(),
        tenantId: TenantId = tenantA,
        name: String = "Test Account",
        type: AccountType = AccountType.ASSET,
    ) = Account.create(
        id = id,
        tenantId = tenantId,
        name = name,
        currency = FiatCurrency.BRL,
        type = type,
        createdAt = now,
        createdBy = "sk_live_test",
        description = "Test account",
    )

    @Test
    fun `save and findById round-trip preserves all fields`() {
        val original = account()
        adapter.save(original)

        val found = adapter.findById(original.id, tenantA)

        assertNotNull(found)
        assertEquals(original.id, found.id)
        assertEquals(original.tenantId, found.tenantId)
        assertEquals(original.name, found.name)
        assertEquals(original.description, found.description)
        assertEquals(original.currency, found.currency)
        assertEquals(original.type, found.type)
        assertEquals(original.normalBalance, found.normalBalance)
        assertEquals(original.createdBy, found.createdBy)
    }

    @Test
    fun `findById with wrong tenant returns null (RLS)`() {
        val acc = account(tenantId = tenantA)
        adapter.save(acc)

        val result = adapter.findById(acc.id, tenantB)

        assertNull(result)
    }

    @Test
    fun `existsById returns true for own tenant and false for other`() {
        val acc = account(tenantId = tenantA)
        adapter.save(acc)

        assertTrue(adapter.existsById(acc.id, tenantA))
        assertFalse(adapter.existsById(acc.id, tenantB))
    }

    @Test
    fun `findAllByTenantId returns only own tenant accounts`() {
        adapter.save(account(name = "A1", tenantId = tenantA))
        adapter.save(account(name = "A2", tenantId = tenantA))
        adapter.save(account(name = "B1", tenantId = tenantB))

        val results = adapter.findAllByTenantId(tenantA)

        assertEquals(2, results.size)
        assertTrue(results.all { it.tenantId == tenantA })
    }

    @Test
    fun `findExistingIds returns only ids that exist for the tenant`() {
        val acc1 = account(tenantId = tenantA)
        val acc2 = account(tenantId = tenantA)
        val accB = account(tenantId = tenantB)
        adapter.save(acc1)
        adapter.save(acc2)
        adapter.save(accB)

        val missing = AccountId.generate()
        val result = adapter.findExistingIds(setOf(acc1.id, acc2.id, accB.id, missing), tenantA)

        assertEquals(setOf(acc1.id, acc2.id), result)
    }

    @Test
    fun `findExistingIds with empty set returns empty set without hitting the database`() {
        val result = adapter.findExistingIds(emptySet(), tenantA)
        assertTrue(result.isEmpty())
    }
}
