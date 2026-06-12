package finance.idem.infrastructure.persistence

import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountType
import finance.idem.core.ledger.JournalLine
import finance.idem.core.ledger.Transaction
import finance.idem.core.monetary.FiatEntry
import org.junit.jupiter.api.BeforeEach
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
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(
    JournalLineRepositoryAdapter::class,
    TransactionRepositoryAdapter::class,
    AccountRepositoryAdapter::class,
    PersistenceTestConfig::class,
)
class JournalLineRepositoryAdapterTest {

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

    @Autowired lateinit var journalLineAdapter: JournalLineRepositoryAdapter
    @Autowired lateinit var transactionAdapter: TransactionRepositoryAdapter
    @Autowired lateinit var accountAdapter: AccountRepositoryAdapter

    private val tenantA = TenantId.generate()
    private val tenantB = TenantId.generate()
    private val base: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private var debitAccountId: AccountId = AccountId.generate()
    private var creditAccountId: AccountId = AccountId.generate()

    @BeforeEach
    fun createAccounts() {
        debitAccountId = AccountId.generate()
        creditAccountId = AccountId.generate()
        accountAdapter.save(Account.create(debitAccountId, tenantA, "Debit", FiatCurrency.BRL, AccountType.ASSET, base, "test"))
        accountAdapter.save(Account.create(creditAccountId, tenantA, "Credit", FiatCurrency.BRL, AccountType.LIABILITY, base, "test"))
    }

    private fun postTx(tenantId: TenantId, createdAt: Instant, amount: String = "100"): TransactionId {
        val txId = TransactionId.generate()
        val tx = Transaction.create(
            id = txId,
            tenantId = tenantId,
            idempotencyKey = UUID.randomUUID().toString(),
            lines = listOf(
                JournalLine(
                    id = UUID.randomUUID(), transactionId = txId, accountId = debitAccountId, tenantId = tenantId,
                    entryType = EntryType.DEBIT,
                    monetaryEntry = FiatEntry(MonetaryAmount.of(amount), FiatCurrency.BRL, PaymentRail.PIX),
                    createdAt = createdAt, createdBy = "test",
                ),
                JournalLine(
                    id = UUID.randomUUID(), transactionId = txId, accountId = creditAccountId, tenantId = tenantId,
                    entryType = EntryType.CREDIT,
                    monetaryEntry = FiatEntry(MonetaryAmount.of(amount), FiatCurrency.BRL, PaymentRail.PIX),
                    createdAt = createdAt, createdBy = "test",
                ),
            ),
            occurredAt = createdAt, createdAt = createdAt, createdBy = "test",
        )
        transactionAdapter.save(tx)
        return txId
    }

    @Test
    fun `returns entries ordered by createdAt desc then id desc`() {
        postTx(tenantA, base)
        postTx(tenantA, base.plusSeconds(60))
        postTx(tenantA, base.plusSeconds(120))

        val page = journalLineAdapter.findByAccountId(debitAccountId, tenantA, null, null, null, null, limit = 10)

        assertEquals(3, page.size)
        assertEquals(listOf(base.plusSeconds(120), base.plusSeconds(60), base), page.map { it.createdAt })
        assertTrue(page.all { it.entryType == EntryType.DEBIT })
    }

    @Test
    fun `limit truncates results to the most recent rows`() {
        postTx(tenantA, base)
        postTx(tenantA, base.plusSeconds(60))
        postTx(tenantA, base.plusSeconds(120))

        val page = journalLineAdapter.findByAccountId(debitAccountId, tenantA, null, null, null, null, limit = 2)

        assertEquals(2, page.size)
        assertEquals(listOf(base.plusSeconds(120), base.plusSeconds(60)), page.map { it.createdAt })
    }

    @Test
    fun `from and to filter by createdAt range`() {
        postTx(tenantA, base)
        postTx(tenantA, base.plusSeconds(60))
        postTx(tenantA, base.plusSeconds(120))

        val page = journalLineAdapter.findByAccountId(
            debitAccountId, tenantA,
            from = base.plusSeconds(30), to = base.plusSeconds(90),
            afterCreatedAt = null, afterId = null, limit = 10,
        )

        assertEquals(1, page.size)
        assertEquals(base.plusSeconds(60), page.first().createdAt)
    }

    @Test
    fun `keyset cursor walks pages with no overlap or gap`() {
        postTx(tenantA, base)
        postTx(tenantA, base.plusSeconds(60))
        postTx(tenantA, base.plusSeconds(120))
        postTx(tenantA, base.plusSeconds(180))

        val page1 = journalLineAdapter.findByAccountId(debitAccountId, tenantA, null, null, null, null, limit = 2)
        assertEquals(listOf(base.plusSeconds(180), base.plusSeconds(120)), page1.map { it.createdAt })

        val last = page1.last()
        val page2 = journalLineAdapter.findByAccountId(
            debitAccountId, tenantA, null, null,
            afterCreatedAt = last.createdAt, afterId = last.id, limit = 2,
        )

        assertEquals(listOf(base.plusSeconds(60), base), page2.map { it.createdAt })
        val allIds = (page1 + page2).map { it.id }
        assertEquals(allIds.distinct().size, allIds.size)
    }

    @Test
    fun `does not return entries for a different tenant (RLS)`() {
        postTx(tenantA, base)

        val page = journalLineAdapter.findByAccountId(debitAccountId, tenantB, null, null, null, null, limit = 10)

        assertTrue(page.isEmpty())
    }
}
