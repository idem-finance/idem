package finance.idem.api.ledger

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
import kotlin.test.assertNull

class JournalLineResponseTest {

    @Test
    fun `from maps JournalLine fields including description`() {
        val line = JournalLine(
            id = UUID.randomUUID(),
            transactionId = TransactionId.generate(),
            accountId = AccountId.generate(),
            tenantId = TenantId.generate(),
            entryType = EntryType.CREDIT,
            monetaryEntry = FiatEntry(MonetaryAmount.of("10.00"), FiatCurrency.BRL, PaymentRail.PIX),
            description = "Pix received",
            createdAt = Instant.parse("2026-06-01T00:00:00Z"),
            createdBy = "system",
        )

        val dto = JournalLineResponse.from(line)

        assertEquals(line.id, dto.entryId)
        assertEquals(line.transactionId.value, dto.transactionId)
        assertEquals(EntryType.CREDIT, dto.type)
        assertEquals("Pix received", dto.description)
        assertEquals(line.createdAt, dto.createdAt)

        val fullCopy = dto.copy()
        val partialCopy = dto.copy(description = "Other")
        assertEquals(dto, fullCopy)
        assert(dto != partialCopy)
        assertEquals("Other", partialCopy.description)
        assert(dto != null)
        assert(dto.toString().contains("Pix received"))
        assertEquals(dto.hashCode(), fullCopy.hashCode())

        val (entryId, transactionId, type, monetary, description, createdAt) = dto
        assertEquals(line.id, entryId)
        assertEquals(line.transactionId.value, transactionId)
        assertEquals(EntryType.CREDIT, type)
        assertEquals(dto.monetary, monetary)
        assertEquals("Pix received", description)
        assertEquals(line.createdAt, createdAt)
    }

    @Test
    fun `description defaults to null when omitted from constructor`() {
        val monetary = FiatEntryResponse.from(FiatEntry(MonetaryAmount.of("10.00"), FiatCurrency.BRL, PaymentRail.PIX))

        val dto = JournalLineResponse(
            entryId = UUID.randomUUID(),
            transactionId = UUID.randomUUID(),
            type = EntryType.DEBIT,
            monetary = monetary,
            createdAt = Instant.parse("2026-06-01T00:00:00Z"),
        )

        assertNull(dto.description)
    }
}
