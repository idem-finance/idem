package finance.idem.infrastructure.service

import finance.idem.application.ledger.Balance
import finance.idem.application.ledger.BalanceAccountNotFound
import finance.idem.application.ledger.GenerateStatementQuery
import finance.idem.application.ledger.InvalidStatementRange
import finance.idem.application.ledger.GetBalanceQuery
import finance.idem.application.ledger.QueryBalanceUseCase
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
import finance.idem.core.monetary.FiatEntry
import finance.idem.core.monetary.MonetaryEntry
import finance.idem.core.monetary.OnChainEntry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class GenerateStatementServiceTest {

    @Mock lateinit var queryBalanceUseCase: QueryBalanceUseCase
    @Mock lateinit var accountRepository: AccountRepository
    @Mock lateinit var transactionRepository: TransactionRepository

    private lateinit var service: GenerateStatementService

    private val tenantId = TenantId.generate()
    private val accountId = AccountId.generate()
    private val now = Instant.parse("2026-06-01T12:00:00Z")

    @BeforeEach
    fun setUp() {
        service = GenerateStatementService(queryBalanceUseCase, transactionRepository)
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

    private fun balance(amount: String, asOf: Instant) = Balance(
        accountId = accountId, currency = FiatCurrency.BRL, amount = MonetaryAmount.of(amount),
        normalBalance = EntryType.DEBIT, computedAt = asOf,
    )

    private fun otherAccountId() = AccountId.generate()

    @Test
    fun `returns StatementAccountNotFound when account does not exist`() {
        val from = now.minusSeconds(7200)
        val to = now
        whenever(queryBalanceUseCase.execute(GetBalanceQuery(accountId, tenantId, asOf = from)))
            .thenReturn(Result.failure(BalanceAccountNotFound(accountId)))

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

        val realBalanceService = QueryBalanceService(accountRepository, transactionRepository)
        val opening = realBalanceService.execute(GetBalanceQuery(accountId, tenantId, asOf = from)).getOrThrow()
        val closing = realBalanceService.execute(GetBalanceQuery(accountId, tenantId, asOf = to)).getOrThrow()

        whenever(queryBalanceUseCase.execute(GetBalanceQuery(accountId, tenantId, asOf = from)))
            .thenReturn(Result.success(opening))
        whenever(queryBalanceUseCase.execute(GetBalanceQuery(accountId, tenantId, asOf = to)))
            .thenReturn(Result.success(closing))

        val statement = service.execute(GenerateStatementQuery(accountId, tenantId, from, to)).getOrThrow()

        val net = statement.movements.fold(MonetaryAmount.ZERO) { acc2, m ->
            if (m.type == acc.normalBalance) acc2 + m.amount else acc2 - m.amount
        }
        assertEquals(closing.amount, opening.amount + net)
        assertEquals(MonetaryAmount.of("1000"), opening.amount)
        assertEquals(MonetaryAmount.of("1300"), closing.amount)
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

        val realBalanceService = QueryBalanceService(accountRepository, transactionRepository)
        val opening = realBalanceService.execute(GetBalanceQuery(accountId, tenantId, asOf = from)).getOrThrow()
        val closing = realBalanceService.execute(GetBalanceQuery(accountId, tenantId, asOf = to)).getOrThrow()

        whenever(queryBalanceUseCase.execute(GetBalanceQuery(accountId, tenantId, asOf = from)))
            .thenReturn(Result.success(opening))
        whenever(queryBalanceUseCase.execute(GetBalanceQuery(accountId, tenantId, asOf = to)))
            .thenReturn(Result.success(closing))

        val statement = service.execute(GenerateStatementQuery(accountId, tenantId, from, to)).getOrThrow()

        assertEquals(MonetaryAmount.of("300"), opening.amount)
        assertTrue(statement.movements.isEmpty())
    }

    @Test
    fun `transaction occurring exactly at to is included in both movements and closing balance`() {
        val from = now.minusSeconds(3600)
        val to = now
        val other = otherAccountId()

        val txAtTo = tx(to) { id -> listOf(
            line(id, EntryType.DEBIT, brlFiat("150"), accountId),
            line(id, EntryType.CREDIT, brlFiat("150"), other),
        ) }

        whenever(transactionRepository.findByAccountId(accountId, tenantId)).thenReturn(listOf(txAtTo))
        whenever(queryBalanceUseCase.execute(GetBalanceQuery(accountId, tenantId, asOf = from)))
            .thenReturn(Result.success(balance("0", asOf = from)))
        whenever(queryBalanceUseCase.execute(GetBalanceQuery(accountId, tenantId, asOf = to)))
            .thenReturn(Result.success(balance("150", asOf = to)))

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

        whenever(transactionRepository.findByAccountId(accountId, tenantId)).thenReturn(listOf(txOnChain, txUsd))
        whenever(queryBalanceUseCase.execute(GetBalanceQuery(accountId, tenantId, asOf = from)))
            .thenReturn(Result.success(balance("0", asOf = from)))
        whenever(queryBalanceUseCase.execute(GetBalanceQuery(accountId, tenantId, asOf = to)))
            .thenReturn(Result.success(balance("0", asOf = to)))

        val statement = service.execute(GenerateStatementQuery(accountId, tenantId, from, to)).getOrThrow()

        assertTrue(statement.movements.isEmpty())
    }
}
