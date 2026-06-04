package finance.idem.application.ledger

import finance.idem.application.audit.AuditEntry
import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.ledger.JournalLine
import finance.idem.core.ledger.Transaction
import finance.idem.core.monetary.FiatEntry
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PostTransactionModelsTest {

    private val tenantId = TenantId.generate()
    private val debitId = AccountId.generate()
    private val creditId = AccountId.generate()
    private val now = Instant.now()

    private fun transaction(): Transaction {
        val txId = TransactionId.generate()
        val line = { id: AccountId, type: EntryType ->
            JournalLine(UUID.randomUUID(), txId, id, tenantId, type,
                FiatEntry(MonetaryAmount.of("100"), FiatCurrency.BRL, PaymentRail.PIX),
                null, now, "system")
        }
        return Transaction.create(
            id = txId, tenantId = tenantId, idempotencyKey = "k1",
            lines = listOf(line(debitId, EntryType.DEBIT), line(creditId, EntryType.CREDIT)),
            occurredAt = now, createdAt = now, createdBy = "sk_test",
        )
    }

    // ── AuditEntry ────────────────────────────────────────────────────────────

    @Test
    fun `AuditEntry from maps transaction fields correctly`() {
        val tx = transaction()
        val entry = AuditEntry.from(tx, null, "sk_test")

        assertEquals(tx.id, entry.transactionId)
        assertEquals(tx.tenantId, entry.tenantId)
        assertEquals("POST_TRANSACTION", entry.action)
        assertEquals("sk_test", entry.createdBy)
        assertEquals(tx.createdAt, entry.occurredAt)
        assertNull(entry.agentContext)
    }

    // ── WebhookOutboxEntry ────────────────────────────────────────────────────

    @Test
    fun `WebhookOutboxEntry transactionCommitted maps fields correctly`() {
        val tx = transaction()
        val entry = WebhookOutboxEntry.transactionCommitted(tx)

        assertEquals(tx.id, entry.transactionId)
        assertEquals(tx.tenantId, entry.tenantId)
        assertEquals("transaction.committed", entry.eventType)
        assertEquals(tx.occurredAt, entry.occurredAt)
    }

    // ── PostTransactionError ──────────────────────────────────────────────────

    @Test
    fun `AccountNotFound carries accountId and message`() {
        val id = AccountId.generate()
        val error = TransactionAccountNotFound(id)
        assertEquals(id, error.accountId)
        assertIs<PostTransactionError>(error)
    }

    @Test
    fun `IdempotencyConflict carries key and message`() {
        val error = IdempotencyConflict("key-xyz")
        assertEquals("key-xyz", error.key)
        assertIs<PostTransactionError>(error)
    }

    @Test
    fun `InvariantViolation detail equals message`() {
        val error = InvariantViolation("debits != credits")
        assertEquals("debits != credits", error.detail)
        assertEquals(error.detail, error.message)
    }

    // ── PostTransactionCommand + JournalLineRequest ───────────────────────────

    @Test
    fun `PostTransactionCommand holds all fields`() {
        val entry = FiatEntry(MonetaryAmount.of("100"), FiatCurrency.BRL, PaymentRail.PIX)
        val line = JournalLineRequest(debitId, EntryType.DEBIT, entry, "desc")
        val cmd = PostTransactionCommand(
            tenantId = tenantId,
            idempotencyKey = "idem-1",
            lines = listOf(line),
            createdBy = "sk_test",
            metadata = mapOf("ref" to "abc"),
        )

        assertEquals(tenantId, cmd.tenantId)
        assertEquals("idem-1", cmd.idempotencyKey)
        assertEquals(1, cmd.lines.size)
        assertEquals("sk_test", cmd.createdBy)
        assertEquals(mapOf("ref" to "abc"), cmd.metadata)
    }

    @Test
    fun `JournalLineRequest holds all fields`() {
        val entry = FiatEntry(MonetaryAmount.of("50"), FiatCurrency.BRL, PaymentRail.PIX)
        val req = JournalLineRequest(creditId, EntryType.CREDIT, entry, "note")

        assertEquals(creditId, req.accountId)
        assertEquals(EntryType.CREDIT, req.entryType)
        assertEquals(entry, req.monetaryEntry)
        assertEquals("note", req.description)
    }

    @Test
    fun `JournalLineRequest equality and copy`() {
        val entry = FiatEntry(MonetaryAmount.of("50"), FiatCurrency.BRL, PaymentRail.PIX)
        val req = JournalLineRequest(creditId, EntryType.CREDIT, entry)
        val copy = req.copy(entryType = EntryType.DEBIT)

        assertEquals(req, req)
        assertEquals(EntryType.DEBIT, copy.entryType)
        assertEquals(req.accountId, copy.accountId)
    }
}
