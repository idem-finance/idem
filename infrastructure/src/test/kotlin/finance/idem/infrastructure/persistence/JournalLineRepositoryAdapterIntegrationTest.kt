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
import finance.idem.infrastructure.SharedPostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Proves cross-tenant isolation for journal lines at the repository/adapter boundary (idem#275)
 * -- a legitimate adapter call made with the wrong tenant's context must fail closed (empty
 * results / null), never surface another tenant's ledger entries. journal_lines has a composite
 * FK requiring (transaction_id, tenant_id) and (account_id, tenant_id) to match, so a line can
 * never be inserted under the wrong tenant in the first place; this test covers the read side.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    JournalLineRepositoryAdapter::class,
    TransactionRepositoryAdapter::class,
    AccountRepositoryAdapter::class,
    PersistenceTestConfig::class,
)
class JournalLineRepositoryAdapterIntegrationTest : SharedPostgresTestBase() {
    @Autowired lateinit var journalLineAdapter: JournalLineRepositoryAdapter

    @Autowired lateinit var transactionAdapter: TransactionRepositoryAdapter

    @Autowired lateinit var accountAdapter: AccountRepositoryAdapter

    private val tenantA = TenantId.generate()
    private val tenantB = TenantId.generate()

    private fun seedTransaction(tenantId: TenantId): Transaction {
        val debit = AccountId.generate()
        val credit = AccountId.generate()
        val now = Instant.now()
        accountAdapter.save(Account.create(debit, tenantId, "Debit", FiatCurrency.BRL, AccountType.ASSET, now, "test"))
        accountAdapter.save(Account.create(credit, tenantId, "Credit", FiatCurrency.BRL, AccountType.LIABILITY, now, "test"))

        val txId = TransactionId.generate()
        val amount = MonetaryAmount.of("25.00")
        val lines =
            listOf(
                JournalLine(
                    id = UUID.randomUUID(),
                    transactionId = txId,
                    accountId = debit,
                    tenantId = tenantId,
                    entryType = EntryType.DEBIT,
                    monetaryEntry = FiatEntry(amount, FiatCurrency.BRL, PaymentRail.PIX),
                    createdAt = now,
                    createdBy = "test",
                ),
                JournalLine(
                    id = UUID.randomUUID(),
                    transactionId = txId,
                    accountId = credit,
                    tenantId = tenantId,
                    entryType = EntryType.CREDIT,
                    monetaryEntry = FiatEntry(amount, FiatCurrency.BRL, PaymentRail.PIX),
                    createdAt = now,
                    createdBy = "test",
                ),
            )
        val transaction =
            Transaction.create(
                id = txId,
                tenantId = tenantId,
                idempotencyKey = "seed-${txId.value}",
                lines = lines,
                occurredAt = now,
                createdAt = now,
                createdBy = "test",
            )
        return transactionAdapter.save(transaction)
    }

    @Test
    fun `countByAccountId is zero when the account belongs to another tenant`() {
        val tenantBTransaction = seedTransaction(tenantB)
        val tenantBAccountId = tenantBTransaction.lines.first().accountId

        val count = journalLineAdapter.countByAccountId(tenantBAccountId, tenantA)

        assertEquals(0L, count, "tenant A must not be able to count tenant B's journal lines")
    }

    @Test
    fun `countByAccountId reflects the real count for the owning tenant`() {
        val tenantBTransaction = seedTransaction(tenantB)
        val tenantBAccountId = tenantBTransaction.lines.first().accountId

        val count = journalLineAdapter.countByAccountId(tenantBAccountId, tenantB)

        assertEquals(1L, count)
    }

    @Test
    fun `findMostRecentEntry returns null when the account belongs to another tenant`() {
        val tenantBTransaction = seedTransaction(tenantB)
        val tenantBAccountId = tenantBTransaction.lines.first().accountId

        val entry = journalLineAdapter.findMostRecentEntry(tenantBAccountId, tenantA)

        assertNull(entry, "tenant A must not be able to read tenant B's most recent journal entry")
    }

    @Test
    fun `findByAccountId returns empty when the account belongs to another tenant`() {
        val tenantBTransaction = seedTransaction(tenantB)
        val tenantBAccountId = tenantBTransaction.lines.first().accountId

        val entries =
            journalLineAdapter.findByAccountId(
                accountId = tenantBAccountId,
                tenantId = tenantA,
                from = null,
                to = null,
                afterCreatedAt = null,
                afterId = null,
                limit = 50,
            )

        assertEquals(emptyList(), entries, "tenant A must not see any of tenant B's journal lines via a crafted account ID lookup")
    }
}
