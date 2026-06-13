package finance.idem.infrastructure.service

import finance.idem.application.ledger.EntriesAccountNotFound
import finance.idem.application.ledger.EntryCursor
import finance.idem.application.ledger.InvalidCursor
import finance.idem.application.ledger.GetEntriesQuery
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
import finance.idem.core.ledger.JournalLineRepository
import finance.idem.core.monetary.FiatEntry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class GetEntriesServiceTest {

    @Mock lateinit var accountRepository: AccountRepository
    @Mock lateinit var journalLineRepository: JournalLineRepository

    private lateinit var service: GetEntriesService

    private val tenantId = TenantId.generate()
    private val accountId = AccountId.generate()
    private val now = Instant.parse("2026-06-01T12:00:00Z")

    @BeforeEach
    fun setUp() {
        service = GetEntriesService(accountRepository, journalLineRepository)
    }

    private fun account() = Account.create(
        id = accountId, tenantId = tenantId, name = "Nostro BRL",
        currency = FiatCurrency.BRL, type = AccountType.ASSET,
        createdAt = now, createdBy = "system",
    )

    private fun line(createdAt: Instant, id: UUID = UUID.randomUUID()) = JournalLine(
        id = id,
        transactionId = TransactionId.generate(),
        accountId = accountId,
        tenantId = tenantId,
        entryType = EntryType.DEBIT,
        monetaryEntry = FiatEntry(MonetaryAmount.of("100"), FiatCurrency.BRL, PaymentRail.PIX),
        createdAt = createdAt,
        createdBy = "system",
    )

    @Test
    fun `returns EntriesAccountNotFound when account does not exist`() {
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(null)

        val result = service.execute(GetEntriesQuery(accountId, tenantId))

        assertTrue(result.isFailure)
        assertIs<EntriesAccountNotFound>(result.exceptionOrNull())
    }

    @Test
    fun `fewer rows than limit plus one yields no nextCursor`() {
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(account())
        whenever(journalLineRepository.findByAccountId(accountId, tenantId, null, null, null, null, 51))
            .thenReturn(listOf(line(now), line(now.minusSeconds(60))))

        val page = service.execute(GetEntriesQuery(accountId, tenantId)).getOrThrow()

        assertEquals(2, page.entries.size)
        assertNull(page.nextCursor)
    }

    @Test
    fun `extra row beyond limit is dropped and nextCursor encodes last returned row`() {
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(account())
        val rows = (0 until 3).map { line(now.minusSeconds(it.toLong())) }
        whenever(journalLineRepository.findByAccountId(accountId, tenantId, null, null, null, null, 3))
            .thenReturn(rows)

        val page = service.execute(GetEntriesQuery(accountId, tenantId, limit = 2)).getOrThrow()

        assertEquals(2, page.entries.size)
        assertEquals(rows.take(2), page.entries)
        val expectedCursor = EntryCursor(rows[1].createdAt, rows[1].id).encode()
        assertEquals(expectedCursor, page.nextCursor)
    }

    @Test
    fun `invalid cursor returns InvalidCursor`() {
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(account())

        val result = service.execute(GetEntriesQuery(accountId, tenantId, cursor = "not-a-valid-cursor"))

        assertTrue(result.isFailure)
        assertIs<InvalidCursor>(result.exceptionOrNull())
    }

    @Test
    fun `cursor decodes into afterCreatedAt and afterId repository args`() {
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(account())
        val anchor = EntryCursor(now.minusSeconds(120), UUID.randomUUID())
        whenever(journalLineRepository.findByAccountId(any(), any(), isNull(), isNull(), eq(anchor.createdAt), eq(anchor.id), any()))
            .thenReturn(emptyList())

        val page = service.execute(GetEntriesQuery(accountId, tenantId, cursor = anchor.encode())).getOrThrow()

        assertTrue(page.entries.isEmpty())
        verify(journalLineRepository).findByAccountId(accountId, tenantId, null, null, anchor.createdAt, anchor.id, 51)
    }

    @Test
    fun `from and to are passed through to the repository`() {
        whenever(accountRepository.findById(accountId, tenantId)).thenReturn(account())
        val from = now.minusSeconds(3600)
        val to = now
        whenever(journalLineRepository.findByAccountId(accountId, tenantId, from, to, null, null, 51))
            .thenReturn(emptyList())

        service.execute(GetEntriesQuery(accountId, tenantId, from = from, to = to)).getOrThrow()

        verify(journalLineRepository).findByAccountId(accountId, tenantId, from, to, null, null, 51)
    }
}
