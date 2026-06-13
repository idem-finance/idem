package finance.idem.infrastructure.service

import finance.idem.application.ledger.GenerateStatementQuery
import finance.idem.application.ledger.InvalidStatementRange
import finance.idem.application.ledger.StatementAccountNotFound
import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountRepository
import finance.idem.core.ledger.AccountType
import finance.idem.core.ledger.JournalLine
import finance.idem.core.ledger.Transaction
import finance.idem.core.ledger.TransactionRepository
import finance.idem.core.monetary.MonetaryEntry
import finance.idem.core.monetary.FiatEntry
import finance.idem.core.monetary.OnChainEntry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class GenerateStatementServiceTest {

    @Mock lateinit var accountRepository: AccountRepository
    @Mock lateinit var transactionRepository: TransactionRepository

    private lateinit var service: GenerateStatementService

    private val tenantId = TenantId.generate()
    private val accountId = AccountId.generate()
    private val now = Instant.parse("2026-06-01T12:00:00Z")

    @BeforeEach
    fun setUp() {
        service = GenerateStatementService(accountRepository, transactionRepository)
    }

    private fun account() = Account.create(
        id = accountId, tenantId = tenantId, name = "Nostro BRL",
        currency = FiatCurrency.BRL, type = AccountType.ASSET,
        createdAt = now, createdBy = "system",
    )

    private fun brlFiat(amount: String) = FiatEntry(
        amount = MonetaryAmount.of(amount), currency = FiatCurrency.BRL, rail = PaymentRail.PIX,
    )

    private fun usdFiat(amount: String) = FiatEntry(
        amount = MonetaryAmount.of(amount), currency = FiatCurrency.USD, rail = PaymentRail.WIRE,
    )

    private fun line(txId: TransactionId, entryType: EntryType, entry: MonetaryEntry, accId: AccountId = accountId) =
        JournalLine(UUID.randomUUID(), txId, accId, tenantId, entryType, entry, null, now, "system")

    private fun tx(occurredAt: Instant, lineBuilder: (TransactionId) -> List<JournalLine>): Transaction {
        val txId = TransactionId.generate()
        return Transaction.create(
            id = txId, tenantId = tenantId, idempotencyKey = UUID.randomUUID().toString(),
            lines = lineBuilder(txId), occurredAt = occurredAt, createdAt = now, createdBy = "system",
        )
    }

    private fun otherAccountId() = AccountId.generate()

    @Test
    fun `returns StatementAccountNotFound when account does not exist`() {
        val from = now.minusSeconds(7200)
        val to = now
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(null)

        val result = service.execute(GenerateStatementQuery(accountId, tenantId, from, to))

        assertTrue(result.isFailure)
        assertIs<StatementAccountNotFound>(result.exceptionOrNull())
    }

    @Test
    fun `from after to returns InvalidStatementRange`() {
        val from = now
        val to = now.minusSeconds(3600)

        val result = service.execute(GenerateStatementQuery(accountId, tenantId, from, to))

        assertTrue(result.isFailure)
        assertIs<InvalidStatementRange>(result.exceptionOrNull())
    }

    @Test
    fun `opening plus movements equals closing for transactions spanning the boundary`() {
        val from = now.minusSeconds(7200)
        val to = now
        val other = otherAccountId()
        val acc = account()

        val txBefore = tx(from.minusSeconds(60)) { id -> listOf(
            line(id, EntryType.DEBIT, brlFiat("1000"), accountId),
            line(id, EntryType.CREDIT, brlFiat("1000"), other),
        ) }
        val txInRange = tx(from.plusSeconds(60)) { id -> listOf(
            line(id, EntryType.DEBIT, brlFiat("500"), accountId),
            line(id, EntryType.CREDIT, brlFiat("500"), other),
        ) }
        val txAtTo = tx(to) { id -> listOf(
            line(id, EntryType.CREDIT, brlFiat("200"), accountId),
            line(id, EntryType.DEBIT, brlFiat("200"), other),
        ) }

        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(acc)
        whenever(transactionRepository.findByAccountId(accountId, tenantId))
            .thenReturn(listOf(txBefore, txInRange, txAtTo))

        val statement = service.execute(GenerateStatementQuery(accountId, tenantId, from, to)).getOrThrow()

        val net = statement.movements.fold(MonetaryAmount.ZERO) { acc2, m ->
            if (m.type == acc.normalBalance) acc2 + m.amount else acc2 - m.amount
        }
        assertEquals(statement.closingBalance, statement.openingBalance + net)
        assertEquals(MonetaryAmount.of("1000"), statement.openingBalance)
        assertEquals(MonetaryAmount.of("1300"), statement.closingBalance)
    }

    @Test
    fun `transaction occurring exactly at from is excluded from movements but included in opening balance`() {
        val from = now.minusSeconds(3600)
        val to = now
        val other = otherAccountId()
        val acc = account()

        val txAtFrom = tx(from) { id -> listOf(
            line(id, EntryType.DEBIT, brlFiat("300"), accountId),
            line(id, EntryType.CREDIT, brlFiat("300"), other),
        ) }

        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(acc)
        whenever(transactionRepository.findByAccountId(accountId, tenantId)).thenReturn(listOf(txAtFrom))

        val statement = service.execute(GenerateStatementQuery(accountId, tenantId, from, to)).getOrThrow()

        assertEquals(MonetaryAmount.of("300"), statement.openingBalance)
        assertTrue(statement.movements.isEmpty())
    }

    @Test
    fun `transaction occurring exactly at to is included in both movements and closing balance`() {
        val from = now.minusSeconds(3600)
        val to = now
        val other = otherAccountId()
        val acc = account()

        val txAtTo = tx(to) { id -> listOf(
            line(id, EntryType.DEBIT, brlFiat("150"), accountId),
            line(id, EntryType.CREDIT, brlFiat("150"), other),
        ) }

        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(acc)
        whenever(transactionRepository.findByAccountId(accountId, tenantId)).thenReturn(listOf(txAtTo))

        val statement = service.execute(GenerateStatementQuery(accountId, tenantId, from, to)).getOrThrow()

        assertEquals(1, statement.movements.size)
        assertEquals(MonetaryAmount.of("150"), statement.movements.single().amount)
        assertEquals(MonetaryAmount.of("150"), statement.closingBalance)
    }

    @Test
    fun `on-chain and mismatched-currency lines on the account are excluded from movements`() {
        val from = now.minusSeconds(3600)
        val to = now
        val other = otherAccountId()
        val acc = account()

        val onChainEntry = OnChainEntry(
            amount = MonetaryAmount.of("180.00"), token = StablecoinToken.USDC,
            chainId = ChainId.EVM, txHash = "0xabc", blockNumber = 19_000_000L,
            walletAddress = "0xWallet", tokenContract = "0xContract",
        )

        val txOnChain = tx(from.plusSeconds(60)) { id -> listOf(
            line(id, EntryType.DEBIT, onChainEntry, accountId),
            line(id, EntryType.CREDIT, onChainEntry, other),
        ) }
        val txUsd = tx(from.plusSeconds(120)) { id -> listOf(
            line(id, EntryType.DEBIT, usdFiat("100"), accountId),
            line(id, EntryType.CREDIT, usdFiat("100"), other),
        ) }

        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(acc)
        whenever(transactionRepository.findByAccountId(accountId, tenantId)).thenReturn(listOf(txOnChain, txUsd))

        val statement = service.execute(GenerateStatementQuery(accountId, tenantId, from, to)).getOrThrow()

        assertTrue(statement.movements.isEmpty())
    }

    @Test
    fun `findByAccountId is called exactly once per request`() {
        val from = now.minusSeconds(3600)
        val to = now

        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(account())
        whenever(transactionRepository.findByAccountId(accountId, tenantId)).thenReturn(emptyList())

        service.execute(GenerateStatementQuery(accountId, tenantId, from, to))

        verify(transactionRepository, times(1)).findByAccountId(any(), any())
    }
}
