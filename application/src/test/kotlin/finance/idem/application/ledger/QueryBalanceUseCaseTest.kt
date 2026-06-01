package finance.idem.application.ledger

import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountRepository
import finance.idem.core.ledger.AccountType
import finance.idem.core.ledger.JournalLine
import finance.idem.core.ledger.Transaction
import finance.idem.core.ledger.TransactionRepository
import finance.idem.core.monetary.MonetaryEntry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class QueryBalanceUseCaseTest {

    @Mock lateinit var accountRepository: AccountRepository
    @Mock lateinit var transactionRepository: TransactionRepository

    private lateinit var useCase: QueryBalanceUseCase

    private val tenantId = TenantId.generate()
    private val accountId = AccountId.generate()
    private val now = Instant.now()

    @BeforeEach
    fun setUp() {
        useCase = QueryBalanceUseCase(accountRepository, transactionRepository)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun assetAccount() = Account.create(
        id = accountId,
        tenantId = tenantId,
        name = "Nostro BRL",
        currency = FiatCurrency.BRL,
        type = AccountType.ASSET,
        createdAt = now,
        createdBy = "system",
    )

    private fun liabilityAccount() = Account.create(
        id = accountId,
        tenantId = tenantId,
        name = "Customer BRL Payable",
        currency = FiatCurrency.BRL,
        type = AccountType.LIABILITY,
        createdAt = now,
        createdBy = "system",
    )

    private fun brlFiat(amount: String) = MonetaryEntry.FiatEntry(
        amount = MonetaryAmount.of(amount),
        currency = FiatCurrency.BRL,
        rail = PaymentRail.PIX,
    )

    private fun line(entryType: EntryType, amount: String, accId: AccountId = accountId) = JournalLine(
        id = UUID.randomUUID(),
        transactionId = TransactionId.generate(),
        accountId = accId,
        tenantId = tenantId,
        entryType = entryType,
        monetaryEntry = brlFiat(amount),
        createdAt = now,
        createdBy = "system",
    )

    private fun tx(
        vararg lines: JournalLine,
        occurredAt: Instant = now,
    ): Transaction {
        val txId = TransactionId.generate()
        return Transaction.create(
            id = txId,
            tenantId = tenantId,
            idempotencyKey = UUID.randomUUID().toString(),
            lines = lines.toList(),
            occurredAt = occurredAt,
            createdAt = now,
            createdBy = "system",
        )
    }

    private fun otherAccountId() = AccountId.generate()

    // ── Account not found ─────────────────────────────────────────────────────

    @Test
    fun `returns AccountNotFound when account does not exist`() {
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(null)

        val result = useCase.execute(QueryBalanceQuery(accountId, tenantId))

        assertTrue(result.isFailure)
        val error = assertIs<QueryBalanceError.AccountNotFound>(result.exceptionOrNull())
        assertEquals(accountId, error.accountId)
    }

    // ── Zero-transaction account ──────────────────────────────────────────────

    @Test
    fun `returns zero balance for account with no transactions`() {
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(assetAccount())
        whenever(transactionRepository.findByAccountId(accountId, tenantId)).thenReturn(emptyList())

        val result = useCase.execute(QueryBalanceQuery(accountId, tenantId))

        assertTrue(result.isSuccess)
        val balance = result.getOrThrow()
        assertTrue(balance.amount.isZero())
        assertEquals(FiatCurrency.BRL, balance.currency)
        assertEquals(EntryType.DEBIT, balance.normalBalance)
    }

    // ── DEBIT-normal account (ASSET) ──────────────────────────────────────────

    @Test
    fun `single debit on asset account increases balance`() {
        val other = otherAccountId()
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(assetAccount())
        whenever(transactionRepository.findByAccountId(accountId, tenantId)).thenReturn(listOf(
            tx(line(EntryType.DEBIT, "1000", accountId), line(EntryType.CREDIT, "1000", other)),
        ))

        val result = useCase.execute(QueryBalanceQuery(accountId, tenantId))

        assertEquals(MonetaryAmount.of("1000"), result.getOrThrow().amount)
    }

    @Test
    fun `mixed debits and credits on asset account — net debit balance`() {
        val other = otherAccountId()
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(assetAccount())
        whenever(transactionRepository.findByAccountId(accountId, tenantId)).thenReturn(listOf(
            tx(line(EntryType.DEBIT, "1000", accountId), line(EntryType.CREDIT, "1000", other)),
            tx(line(EntryType.CREDIT, "400", accountId), line(EntryType.DEBIT, "400", other)),
        ))

        val result = useCase.execute(QueryBalanceQuery(accountId, tenantId))

        // ASSET: debits (1000) - credits (400) = 600
        assertEquals(MonetaryAmount.of("600"), result.getOrThrow().amount)
    }

    // ── CREDIT-normal account (LIABILITY) ─────────────────────────────────────

    @Test
    fun `credit on liability account increases balance`() {
        val other = otherAccountId()
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(liabilityAccount())
        whenever(transactionRepository.findByAccountId(accountId, tenantId)).thenReturn(listOf(
            tx(line(EntryType.CREDIT, "500", accountId), line(EntryType.DEBIT, "500", other)),
        ))

        val result = useCase.execute(QueryBalanceQuery(accountId, tenantId))

        // LIABILITY: credits (500) - debits (0) = 500
        assertEquals(MonetaryAmount.of("500"), result.getOrThrow().amount)
    }

    // ── asOf filtering ────────────────────────────────────────────────────────

    @Test
    fun `asOf excludes transactions after the cutoff`() {
        val other = otherAccountId()
        val cutoff = now.minusSeconds(3600)
        val before = now.minusSeconds(7200)
        val after = now

        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(assetAccount())
        whenever(transactionRepository.findByAccountId(accountId, tenantId)).thenReturn(listOf(
            tx(line(EntryType.DEBIT, "1000", accountId), line(EntryType.CREDIT, "1000", other), occurredAt = before),
            tx(line(EntryType.DEBIT, "500", accountId), line(EntryType.CREDIT, "500", other), occurredAt = after),
        ))

        val result = useCase.execute(QueryBalanceQuery(accountId, tenantId, asOf = cutoff))

        // Only the 'before' transaction is included
        assertEquals(MonetaryAmount.of("1000"), result.getOrThrow().amount)
    }

    @Test
    fun `asOf includes transactions exactly at the cutoff instant`() {
        val other = otherAccountId()
        val cutoff = now.minusSeconds(3600)

        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(assetAccount())
        whenever(transactionRepository.findByAccountId(accountId, tenantId)).thenReturn(listOf(
            tx(line(EntryType.DEBIT, "750", accountId), line(EntryType.CREDIT, "750", other), occurredAt = cutoff),
        ))

        val result = useCase.execute(QueryBalanceQuery(accountId, tenantId, asOf = cutoff))

        assertEquals(MonetaryAmount.of("750"), result.getOrThrow().amount)
    }

    // ── Only lines for this account are counted ───────────────────────────────

    @Test
    fun `only lines matching the queried accountId contribute to balance`() {
        val other = otherAccountId()
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(assetAccount())
        whenever(transactionRepository.findByAccountId(accountId, tenantId)).thenReturn(listOf(
            tx(
                line(EntryType.DEBIT,  "1000", accountId),
                line(EntryType.CREDIT, "1000", other),      // other account — must NOT be counted
            ),
        ))

        val result = useCase.execute(QueryBalanceQuery(accountId, tenantId))

        assertEquals(MonetaryAmount.of("1000"), result.getOrThrow().amount)
    }
}
