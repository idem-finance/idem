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
 * Proves cross-tenant isolation at the repository/adapter boundary (idem#275) -- a legitimate
 * adapter call made with the wrong tenant's context must fail closed (return null), never
 * return another tenant's transaction. There is no HTTP GET-by-transaction-id endpoint
 * (TransactionController is POST-only), so this is the only layer that can test "tenant A
 * cannot read tenant B's transaction" directly; see RowLevelSecurityEnforcementTest for the
 * lower-level raw-SQL-injection-style proof that FORCE RLS itself is what makes this hold.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TransactionRepositoryAdapter::class, AccountRepositoryAdapter::class, PersistenceTestConfig::class)
class TransactionRepositoryAdapterIntegrationTest : SharedPostgresTestBase() {
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
        val amount = MonetaryAmount.of("50.00")
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
    fun `findById returns null when the transaction belongs to another tenant`() {
        val tenantBTransaction = seedTransaction(tenantB)

        val result = transactionAdapter.findById(tenantBTransaction.id, tenantA)

        assertNull(result, "tenant A must not be able to read tenant B's transaction, even by exact ID")
    }

    @Test
    fun `findById returns the transaction when queried with its own tenant`() {
        val tenantBTransaction = seedTransaction(tenantB)

        val result = transactionAdapter.findById(tenantBTransaction.id, tenantB)

        assertEquals(tenantBTransaction.id, result?.id, "the owning tenant must still be able to read its own transaction")
    }

    @Test
    fun `findByIdempotencyKey returns null when the key belongs to another tenant`() {
        val tenantBTransaction = seedTransaction(tenantB)

        val result = transactionAdapter.findByIdempotencyKey(tenantBTransaction.idempotencyKey, tenantA)

        assertNull(result, "an idempotency key is scoped per-tenant; tenant A must not resolve tenant B's key")
    }

    @Test
    fun `findByAccountId returns empty when the account belongs to another tenant`() {
        val tenantBTransaction = seedTransaction(tenantB)
        val tenantBAccountId = tenantBTransaction.lines.first().accountId

        val result = transactionAdapter.findByAccountId(tenantBAccountId, tenantA)

        assertEquals(emptyList(), result, "tenant A must not see any transactions for tenant B's account")
    }
}
