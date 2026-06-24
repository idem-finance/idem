package finance.idem.infrastructure.service

import finance.idem.application.ledger.Balance
import finance.idem.application.ledger.DescribeAccountAccountNotFound
import finance.idem.application.ledger.DescribeAccountQuery
import finance.idem.application.ledger.GetBalanceQuery
import finance.idem.application.ledger.GetBalanceUseCase
import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.TenantId
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountRepository
import finance.idem.core.ledger.AccountType
import finance.idem.core.ledger.JournalLine
import finance.idem.core.ledger.JournalLineRepository
import finance.idem.core.monetary.FiatEntry
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class DescribeAccountServiceTest {

    @Mock lateinit var accountRepository: AccountRepository
    @Mock lateinit var journalLineRepository: JournalLineRepository
    @Mock lateinit var getBalanceUseCase: GetBalanceUseCase

    private lateinit var service: DescribeAccountService

    private val tenantId = TenantId.generate()
    private val accountId = AccountId.generate()

    @BeforeEach
    fun setUp() {
        service = DescribeAccountService(accountRepository, journalLineRepository, getBalanceUseCase)
    }

    @Test
    fun `returns failure when account not found`() {
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(null)

        val result = service.execute(DescribeAccountQuery(accountId, tenantId))

        assertTrue(result.isFailure)
        assertIs<DescribeAccountAccountNotFound>(result.exceptionOrNull())
    }

    @Test
    fun `returns AccountDescription with correct fields`() {
        val now = Instant.now()
        val account = Account.reconstitute(
            id = accountId,
            tenantId = tenantId,
            name = "Treasury",
            currency = FiatCurrency.USD,
            type = AccountType.ASSET,
            createdAt = now,
            createdBy = "test",
        )
        val balance = Balance(accountId, FiatCurrency.USD, MonetaryAmount.of("250.00"), EntryType.DEBIT, now)

        val line = JournalLine(
            id = UUID.randomUUID(),
            transactionId = finance.idem.core.TransactionId(UUID.randomUUID()),
            accountId = accountId,
            tenantId = tenantId,
            entryType = EntryType.DEBIT,
            monetaryEntry = FiatEntry(MonetaryAmount.of("100"), FiatCurrency.USD, finance.idem.core.PaymentRail.WIRE),
            description = null,
            createdAt = now,
            createdBy = "test",
        )

        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(account)
        whenever(journalLineRepository.countByAccountId(accountId, tenantId)).thenReturn(7L)
        whenever(journalLineRepository.findMostRecentEntry(accountId, tenantId)).thenReturn(line)
        whenever(getBalanceUseCase.execute(any())).thenReturn(Result.success(balance))

        val result = service.execute(DescribeAccountQuery(accountId, tenantId))

        assertTrue(result.isSuccess)
        val desc = result.getOrThrow()
        assertEquals(accountId, desc.accountId)
        assertEquals("Treasury", desc.name)
        assertNull(desc.description)
        assertEquals(FiatCurrency.USD, desc.currency)
        assertEquals(7L, desc.entryCount)
        assertEquals(now, desc.lastActivityAt)
        assertEquals(MonetaryAmount.of("250.00"), desc.balance.amount)
    }

    @Test
    fun `lastActivityAt is null when account has no entries`() {
        val now = Instant.now()
        val account = Account.reconstitute(
            id = accountId,
            tenantId = tenantId,
            name = "Empty",
            currency = FiatCurrency.BRL,
            type = AccountType.LIABILITY,
            createdAt = now,
            createdBy = "test",
        )
        val balance = Balance(accountId, FiatCurrency.BRL, MonetaryAmount.ZERO, EntryType.CREDIT, now)

        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(account)
        whenever(journalLineRepository.countByAccountId(accountId, tenantId)).thenReturn(0L)
        whenever(journalLineRepository.findMostRecentEntry(accountId, tenantId)).thenReturn(null)
        whenever(getBalanceUseCase.execute(any())).thenReturn(Result.success(balance))

        val result = service.execute(DescribeAccountQuery(accountId, tenantId))

        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow().lastActivityAt)
    }
}
