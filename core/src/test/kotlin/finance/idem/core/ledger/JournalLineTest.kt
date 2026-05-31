package finance.idem.core.ledger

import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.TransactionId
import finance.idem.core.monetary.MonetaryEntry
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class JournalLineTest {

    private val now = Instant.now()
    private val fiatEntry = MonetaryEntry.FiatEntry(
        amount = MonetaryAmount.of("1000.00"),
        currency = FiatCurrency.BRL,
        rail = PaymentRail.PIX,
    )

    @Test
    fun `constructs with all required fields`() {
        val line = JournalLine(
            id = UUID.randomUUID(),
            transactionId = TransactionId.generate(),
            accountId = AccountId.generate(),
            entryType = EntryType.DEBIT,
            monetaryEntry = fiatEntry,
            createdAt = now,
            createdBy = "sk_live_xxxx",
        )
        assertEquals(EntryType.DEBIT, line.entryType)
        assertEquals(fiatEntry, line.monetaryEntry)
        assertEquals("sk_live_xxxx", line.createdBy)
        assertEquals(now, line.createdAt)
    }

    @Test
    fun `description defaults to null`() {
        val line = JournalLine(
            id = UUID.randomUUID(),
            transactionId = TransactionId.generate(),
            accountId = AccountId.generate(),
            entryType = EntryType.CREDIT,
            monetaryEntry = fiatEntry,
            createdAt = now,
            createdBy = "system",
        )
        assertNull(line.description)
    }

    @Test
    fun `constructs with optional description`() {
        val line = JournalLine(
            id = UUID.randomUUID(),
            transactionId = TransactionId.generate(),
            accountId = AccountId.generate(),
            entryType = EntryType.CREDIT,
            monetaryEntry = fiatEntry,
            description = "Nostro BRL PIX leg",
            createdAt = now,
            createdBy = "system",
        )
        assertEquals("Nostro BRL PIX leg", line.description)
    }

    @Test
    fun `is immutable — no updatedAt or updatedBy fields`() {
        val line = JournalLine(
            id = UUID.randomUUID(),
            transactionId = TransactionId.generate(),
            accountId = AccountId.generate(),
            entryType = EntryType.DEBIT,
            monetaryEntry = fiatEntry,
            createdAt = now,
            createdBy = "system",
        )
        val fields = line::class.members.map { it.name }.toSet()
        assertFalse("updatedAt" in fields, "JournalLine must not have updatedAt — it is immutable")
        assertFalse("updatedBy" in fields, "JournalLine must not have updatedBy — it is immutable")
    }

    @Test
    fun `equality is structural`() {
        val id = UUID.randomUUID()
        val txId = TransactionId.generate()
        val accId = AccountId.generate()
        val line1 = JournalLine(id, txId, accId, EntryType.DEBIT, fiatEntry, null, now, "system")
        val line2 = JournalLine(id, txId, accId, EntryType.DEBIT, fiatEntry, null, now, "system")
        assertEquals(line1, line2)
    }
}
