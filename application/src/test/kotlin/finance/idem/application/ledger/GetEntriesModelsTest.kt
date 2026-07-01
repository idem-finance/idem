package finance.idem.application.ledger

import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.ledger.JournalLine
import finance.idem.core.monetary.FiatEntry
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class GetEntriesModelsTest {
    private val accountId = AccountId.generate()
    private val tenantId = TenantId.generate()
    private val now = Instant.now()

    @Test
    fun `GetEntriesQuery holds all fields`() {
        val query = GetEntriesQuery(accountId, tenantId, from = now, to = now, limit = 25, cursor = "abc")
        assertEquals(accountId, query.accountId)
        assertEquals(tenantId, query.tenantId)
        assertEquals(now, query.from)
        assertEquals(now, query.to)
        assertEquals(25, query.limit)
        assertEquals("abc", query.cursor)
        assertEquals(query, query.copy())
    }

    @Test
    fun `GetEntriesQuery defaults limit to 50 and optional fields to null`() {
        val query = GetEntriesQuery(accountId, tenantId)
        assertEquals(50, query.limit)
        assertNull(query.from)
        assertNull(query.to)
        assertNull(query.cursor)
    }

    @Test
    fun `EntryPage holds all fields`() {
        val line =
            JournalLine(
                id = UUID.randomUUID(),
                transactionId = TransactionId.generate(),
                accountId = accountId,
                tenantId = tenantId,
                entryType = EntryType.DEBIT,
                monetaryEntry = FiatEntry(MonetaryAmount.of("100"), FiatCurrency.BRL, PaymentRail.PIX),
                createdAt = now,
                createdBy = "test",
            )

        val page = EntryPage(accountId, listOf(line), "next-cursor")

        assertEquals(accountId, page.accountId)
        assertEquals(listOf(line), page.entries)
        assertEquals("next-cursor", page.nextCursor)
        assertEquals(page, page.copy())
    }

    @Test
    fun `EntryPage nextCursor may be null`() {
        val page = EntryPage(accountId, emptyList(), null)

        assertNull(page.nextCursor)
    }

    @Test
    fun `EntriesAccountNotFound carries accountId and message`() {
        val error = EntriesAccountNotFound(accountId)

        assertEquals(accountId, error.accountId)
        assertIs<GetEntriesError>(error)
    }

    @Test
    fun `InvalidCursor carries cursor and message`() {
        val error = InvalidCursor("bad-token")

        assertEquals("bad-token", error.cursor)
        assertIs<GetEntriesError>(error)
    }
}
