package finance.idem.infrastructure.service

import finance.idem.application.ledger.BalanceAccountNotFound
import finance.idem.application.ledger.GetBalanceQuery
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
import finance.idem.core.monetary.FiatEntry
import finance.idem.core.monetary.OnChainEntry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class QueryBalanceServiceTest {

    @Mock lateinit var accountRepository: AccountRepository
    @Mock lateinit var transactionRepository: TransactionRepository

    private lateinit var service: QueryBalanceService

    private val tenantId = TenantId.generate()
    private val accountId = AccountId.generate()
    private val now = Instant.parse("2026-06-01T12:00:00Z")
    private val fixedClock = Clock.fixed(now, ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        service = QueryBalanceService(accountRepository, transactionRepository, fixedClock)
    }

    private fun assetAccount() = Account.create(
        id = accountId, tenantId = tenantId, name = "Nostro BRL",
        currency = FiatCurrency.BRL, type = AccountType.ASSET,
        createdAt = now, createdBy = "system",
    )

    private fun liabilityAccount() = Account.create(
        id = accountId, tenantId = tenantId, name = "Customer BRL Payable",
        currency = FiatCurrency.BRL, type = AccountType.LIABILITY,
        createdAt = now, createdBy = "system",
    )

    private fun brlFiat(amount: String) = FiatEntry(
        amount = MonetaryAmount.of(amount), currency = FiatCurrency.BRL, rail = PaymentRail.PIX,
    )

    private fun line(txId: TransactionId, entryType: EntryType, amount: String, accId: AccountId = accountId) =
        JournalLine(UUID.randomUUID(), txId, accId, tenantId, entryType, brlFiat(amount), null, now, "system")

    private fun tx(lineBuilder: (TransactionId) -> List<JournalLine>, occurredAt: Instant = now): Transaction {
        val txId = TransactionId.generate()
        return Transaction.create(
            id = txId, tenantId = tenantId, idempotencyKey = UUID.randomUUID().toString(),
            lines = lineBuilder(txId), occurredAt = occurredAt, createdAt = now, createdBy = "system",
        )
    }

    private fun otherAccountId() = AccountId.generate()

    @Test
    fun `returns AccountNotFound when account does not exist`() {
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(null)
        val result = service.execute(GetBalanceQuery(accountId, tenantId))
        assertTrue(result.isFailure)
        assertIs<BalanceAccountNotFound>(result.exceptionOrNull())
    }

    @Test
    fun `returns zero balance for account with no transactions`() {
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(assetAccount())
        whenever(transactionRepository.findByAccountId(accountId, tenantId)).thenReturn(emptyList())
        val balance = service.execute(GetBalanceQuery(accountId, tenantId)).getOrThrow()
        assertTrue(balance.amount.isZero())
        assertEquals(FiatCurrency.BRL, balance.currency)
        assertEquals(EntryType.DEBIT, balance.normalBalance)
    }

    @Test
    fun `single debit on asset account increases balance`() {
        val other = otherAccountId()
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(assetAccount())
        whenever(transactionRepository.findByAccountId(accountId, tenantId)).thenReturn(listOf(
            tx({ id -> listOf(line(id, EntryType.DEBIT, "1000", accountId), line(id, EntryType.CREDIT, "1000", other)) }),
        ))
        assertEquals(MonetaryAmount.of("1000"), service.execute(GetBalanceQuery(accountId, tenantId)).getOrThrow().amount)
    }

    @Test
    fun `mixed debits and credits on asset account — net debit balance`() {
        val other = otherAccountId()
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(assetAccount())
        whenever(transactionRepository.findByAccountId(accountId, tenantId)).thenReturn(listOf(
            tx({ id -> listOf(line(id, EntryType.DEBIT, "1000", accountId), line(id, EntryType.CREDIT, "1000", other)) }),
            tx({ id -> listOf(line(id, EntryType.CREDIT, "400", accountId), line(id, EntryType.DEBIT, "400", other)) }),
        ))
        assertEquals(MonetaryAmount.of("600"), service.execute(GetBalanceQuery(accountId, tenantId)).getOrThrow().amount)
    }

    @Test
    fun `credit on liability account increases balance`() {
        val other = otherAccountId()
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(liabilityAccount())
        whenever(transactionRepository.findByAccountId(accountId, tenantId)).thenReturn(listOf(
            tx({ id -> listOf(line(id, EntryType.CREDIT, "500", accountId), line(id, EntryType.DEBIT, "500", other)) }),
        ))
        assertEquals(MonetaryAmount.of("500"), service.execute(GetBalanceQuery(accountId, tenantId)).getOrThrow().amount)
    }

    @Test
    fun `asOf excludes transactions after the cutoff`() {
        val other = otherAccountId()
        val cutoff = now.minusSeconds(3600)
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(assetAccount())
        whenever(transactionRepository.findByAccountId(accountId, tenantId)).thenReturn(listOf(
            tx({ id -> listOf(line(id, EntryType.DEBIT, "1000", accountId), line(id, EntryType.CREDIT, "1000", other)) }, occurredAt = now.minusSeconds(7200)),
            tx({ id -> listOf(line(id, EntryType.DEBIT, "500", accountId), line(id, EntryType.CREDIT, "500", other)) }, occurredAt = now),
        ))
        assertEquals(MonetaryAmount.of("1000"), service.execute(GetBalanceQuery(accountId, tenantId, asOf = cutoff)).getOrThrow().amount)
    }

    @Test
    fun `asOf includes transactions exactly at the cutoff instant`() {
        val other = otherAccountId()
        val cutoff = now.minusSeconds(3600)
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(assetAccount())
        whenever(transactionRepository.findByAccountId(accountId, tenantId)).thenReturn(listOf(
            tx({ id -> listOf(line(id, EntryType.DEBIT, "750", accountId), line(id, EntryType.CREDIT, "750", other)) }, occurredAt = cutoff),
        ))
        assertEquals(MonetaryAmount.of("750"), service.execute(GetBalanceQuery(accountId, tenantId, asOf = cutoff)).getOrThrow().amount)
    }

    @Test
    fun `only lines matching the queried accountId contribute to balance`() {
        val other = otherAccountId()
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(assetAccount())
        whenever(transactionRepository.findByAccountId(accountId, tenantId)).thenReturn(listOf(
            tx({ id -> listOf(line(id, EntryType.DEBIT, "1000", accountId), line(id, EntryType.CREDIT, "1000", other)) }),
        ))
        assertEquals(MonetaryAmount.of("1000"), service.execute(GetBalanceQuery(accountId, tenantId)).getOrThrow().amount)
    }

    @Test
    fun `on-chain entries are excluded from fiat balance`() {
        val other = otherAccountId()
        val onChainEntry = OnChainEntry(
            amount = MonetaryAmount.of("180.00"), token = finance.idem.core.StablecoinToken.USDC,
            chainId = finance.idem.core.ChainId.EVM, txHash = "0xabc", blockNumber = 19_000_000L,
            walletAddress = "0xWallet", tokenContract = "0xContract",
        )
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(assetAccount())
        whenever(transactionRepository.findByAccountId(accountId, tenantId)).thenReturn(listOf(
            tx({ id -> listOf(
                JournalLine(UUID.randomUUID(), id, accountId, tenantId, EntryType.DEBIT, onChainEntry, null, now, "system"),
                JournalLine(UUID.randomUUID(), id, other, tenantId, EntryType.CREDIT, onChainEntry, null, now, "system"),
            )}),
        ))
        assertTrue(service.execute(GetBalanceQuery(accountId, tenantId)).getOrThrow().amount.isZero())
    }

    @Test
    fun `computedAt reflects the injected clock`() {
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(assetAccount())
        whenever(transactionRepository.findByAccountId(accountId, tenantId)).thenReturn(emptyList())
        assertEquals(now, service.execute(GetBalanceQuery(accountId, tenantId)).getOrThrow().computedAt)
    }
}
