package finance.idem.infrastructure.service

import finance.idem.application.audit.AuditEntry
import finance.idem.application.ledger.JournalLineRequest
import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.application.ledger.PostTransactionError
import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.application.port.AuditRepository
import finance.idem.application.port.IdempotencyStore
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.ledger.AccountRepository
import finance.idem.core.ledger.JournalLine
import finance.idem.core.ledger.Transaction
import finance.idem.core.ledger.TransactionRepository
import finance.idem.core.ledger.TransactionStatus
import finance.idem.core.monetary.MonetaryEntry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class PostTransactionServiceTest {

    @Mock lateinit var transactionRepository: TransactionRepository
    @Mock lateinit var accountRepository: AccountRepository
    @Mock lateinit var auditRepository: AuditRepository
    @Mock lateinit var webhookOutboxRepository: WebhookOutboxRepository
    @Mock lateinit var idempotencyStore: IdempotencyStore

    private lateinit var service: PostTransactionService

    private val tenantId = TenantId.generate()
    private val debitAccountId = AccountId.generate()
    private val creditAccountId = AccountId.generate()

    @BeforeEach
    fun setUp() {
        service = PostTransactionService(
            transactionRepository,
            accountRepository,
            auditRepository,
            webhookOutboxRepository,
            idempotencyStore,
        )
    }

    private fun brlLine(accountId: AccountId, entryType: EntryType) = JournalLineRequest(
        accountId = accountId,
        entryType = entryType,
        monetaryEntry = MonetaryEntry.FiatEntry(
            amount = MonetaryAmount.of("1000.00"),
            currency = FiatCurrency.BRL,
            rail = PaymentRail.PIX,
        ),
    )

    private fun command(
        idempotencyKey: String = "idem-001",
        lines: List<JournalLineRequest> = listOf(
            brlLine(debitAccountId, EntryType.DEBIT),
            brlLine(creditAccountId, EntryType.CREDIT),
        ),
    ) = PostTransactionCommand(
        tenantId = tenantId,
        idempotencyKey = idempotencyKey,
        lines = lines,
        createdBy = "sk_live_xxxx",
    )

    private fun stubAccountsExist() {
        whenever(accountRepository.findExistingIds(any(), any()))
            .thenReturn(setOf(debitAccountId, creditAccountId))
    }

    private fun stubSave(capturedTx: MutableList<Transaction> = mutableListOf()): MutableList<Transaction> {
        whenever(transactionRepository.save(any())).thenAnswer { inv ->
            val tx = inv.getArgument<Transaction>(0)
            capturedTx.add(tx)
            tx
        }
        return capturedTx
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    fun `executes successfully and returns TransactionId`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        stubAccountsExist()
        val saved = stubSave()

        val result = service.execute(command())

        assertTrue(result.isSuccess)
        assertNotNull(result.getOrNull())
        assertEquals(saved.first().id, result.getOrNull())
    }

    @Test
    fun `all four writes are performed on success`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        stubAccountsExist()
        stubSave()

        service.execute(command())

        verify(transactionRepository).save(any())
        verify(auditRepository).save(any<AuditEntry>())
        verify(webhookOutboxRepository).save(any<WebhookOutboxEntry>())
        verify(idempotencyStore).tryRecord(any(), any(), any())
    }

    @Test
    fun `saved transaction has COMMITTED status`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        stubAccountsExist()
        val saved = stubSave()

        service.execute(command())

        assertEquals(TransactionStatus.COMMITTED, saved.first().status)
    }

    @Test
    fun `saved transaction carries correct tenant and idempotency key`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        stubAccountsExist()
        val saved = stubSave()

        service.execute(command(idempotencyKey = "my-key-42"))

        assertEquals(tenantId, saved.first().tenantId)
        assertEquals("my-key-42", saved.first().idempotencyKey)
    }

    // ── Idempotency ───────────────────────────────────────────────────────────

    @Test
    fun `returns existing TransactionId when key already committed`() {
        val existingId = TransactionId.generate()
        val existingTx = Transaction.create(
            id = existingId, tenantId = tenantId, idempotencyKey = "idem-001",
            lines = listOf(
                JournalLine(UUID.randomUUID(), existingId, debitAccountId, tenantId, EntryType.DEBIT,
                    MonetaryEntry.FiatEntry(MonetaryAmount.of("500"), FiatCurrency.BRL, PaymentRail.PIX), null, Instant.now(), "system"),
                JournalLine(UUID.randomUUID(), existingId, creditAccountId, tenantId, EntryType.CREDIT,
                    MonetaryEntry.FiatEntry(MonetaryAmount.of("500"), FiatCurrency.BRL, PaymentRail.PIX), null, Instant.now(), "system"),
            ),
            occurredAt = Instant.now(), createdAt = Instant.now(), createdBy = "system",
        ).copy(status = TransactionStatus.COMMITTED)

        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(false)
        whenever(idempotencyStore.find("idem-001", tenantId)).thenReturn(existingId)
        whenever(transactionRepository.findById(existingId, tenantId)).thenReturn(existingTx)

        val result = service.execute(command())

        assertTrue(result.isSuccess)
        assertEquals(existingId, result.getOrNull())
        verify(transactionRepository, never()).save(any())
        verify(auditRepository, never()).save(any())
    }

    @Test
    fun `returns IdempotencyConflict when key exists but transaction is PENDING`() {
        val existingId = TransactionId.generate()
        val pendingTx = Transaction.create(
            id = existingId, tenantId = tenantId, idempotencyKey = "idem-001",
            lines = listOf(
                JournalLine(UUID.randomUUID(), existingId, debitAccountId, tenantId, EntryType.DEBIT,
                    MonetaryEntry.FiatEntry(MonetaryAmount.of("500"), FiatCurrency.BRL, PaymentRail.PIX), null, Instant.now(), "system"),
                JournalLine(UUID.randomUUID(), existingId, creditAccountId, tenantId, EntryType.CREDIT,
                    MonetaryEntry.FiatEntry(MonetaryAmount.of("500"), FiatCurrency.BRL, PaymentRail.PIX), null, Instant.now(), "system"),
            ),
            occurredAt = Instant.now(), createdAt = Instant.now(), createdBy = "system",
        )

        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(false)
        whenever(idempotencyStore.find("idem-001", tenantId)).thenReturn(existingId)
        whenever(transactionRepository.findById(existingId, tenantId)).thenReturn(pendingTx)

        val result = service.execute(command())

        assertTrue(result.isFailure)
        assertIs<PostTransactionError.IdempotencyConflict>(result.exceptionOrNull())
    }

    @Test
    fun `proceeds when previous transaction was ROLLED_BACK — releases and retries key`() {
        val rolledBackId = TransactionId.generate()
        val rolledBackTx = Transaction.create(
            id = rolledBackId, tenantId = tenantId, idempotencyKey = "idem-001",
            lines = listOf(
                JournalLine(UUID.randomUUID(), rolledBackId, debitAccountId, tenantId, EntryType.DEBIT,
                    MonetaryEntry.FiatEntry(MonetaryAmount.of("500"), FiatCurrency.BRL, PaymentRail.PIX), null, Instant.now(), "system"),
                JournalLine(UUID.randomUUID(), rolledBackId, creditAccountId, tenantId, EntryType.CREDIT,
                    MonetaryEntry.FiatEntry(MonetaryAmount.of("500"), FiatCurrency.BRL, PaymentRail.PIX), null, Instant.now(), "system"),
            ),
            occurredAt = Instant.now(), createdAt = Instant.now(), createdBy = "system",
        ).copy(status = TransactionStatus.ROLLED_BACK)

        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(false).thenReturn(true)
        whenever(idempotencyStore.find("idem-001", tenantId)).thenReturn(rolledBackId)
        whenever(transactionRepository.findById(rolledBackId, tenantId)).thenReturn(rolledBackTx)
        stubAccountsExist()
        stubSave()

        val result = service.execute(command())

        assertTrue(result.isSuccess)
        verify(idempotencyStore).release("idem-001", tenantId)
        verify(transactionRepository).save(any())
    }

    // ── Account validation ────────────────────────────────────────────────────

    @Test
    fun `returns AccountNotFound when an account does not exist`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        whenever(accountRepository.findExistingIds(any(), any())).thenReturn(setOf(creditAccountId))

        val result = service.execute(command())

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertIs<PostTransactionError.AccountNotFound>(error)
        assertEquals(debitAccountId, error.accountId)
        verify(transactionRepository, never()).save(any())
    }

    // ── Double-entry invariant ────────────────────────────────────────────────

    @Test
    fun `returns InvariantViolation for unbalanced lines`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        whenever(accountRepository.findExistingIds(any(), any())).thenAnswer { inv ->
            inv.getArgument<Set<*>>(0).toSet()
        }

        val result = service.execute(command(lines = listOf(
            JournalLineRequest(debitAccountId, EntryType.DEBIT,
                MonetaryEntry.FiatEntry(MonetaryAmount.of("1000"), FiatCurrency.BRL, PaymentRail.PIX)),
            JournalLineRequest(creditAccountId, EntryType.CREDIT,
                MonetaryEntry.FiatEntry(MonetaryAmount.of("999"), FiatCurrency.BRL, PaymentRail.PIX)),
        )))

        assertTrue(result.isFailure)
        assertIs<PostTransactionError.InvariantViolation>(result.exceptionOrNull())
        verify(transactionRepository, never()).save(any())
    }

    @Test
    fun `returns InvariantViolation for single line`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        whenever(accountRepository.findExistingIds(any(), any())).thenAnswer { inv ->
            inv.getArgument<Set<*>>(0).toSet()
        }

        val result = service.execute(command(lines = listOf(brlLine(debitAccountId, EntryType.DEBIT))))

        assertTrue(result.isFailure)
        val error = assertIs<PostTransactionError.InvariantViolation>(result.exceptionOrNull())
        assertNotNull(error.message)
    }

    @Test
    fun `AuditEntry saved with correct transaction and actor fields`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        stubAccountsExist()
        stubSave()
        val captor = argumentCaptor<AuditEntry>()

        service.execute(command())

        verify(auditRepository).save(captor.capture())
        val entry = captor.firstValue
        assertEquals(tenantId, entry.tenantId)
        assertEquals("POST_TRANSACTION", entry.action)
        assertEquals("sk_live_xxxx", entry.createdBy)
        assertNotNull(entry.transactionId)
    }

    @Test
    fun `InvariantViolation detail is accessible`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        whenever(accountRepository.findExistingIds(any(), any())).thenAnswer { inv ->
            inv.getArgument<Set<*>>(0).toSet()
        }

        val result = service.execute(command(lines = listOf(brlLine(debitAccountId, EntryType.DEBIT))))

        val error = assertIs<PostTransactionError.InvariantViolation>(result.exceptionOrNull())
        assertNotNull(error.detail)
        assertEquals(error.detail, error.message)
    }

    @Test
    fun `WebhookOutboxEntry transactionCommitted carries correct fields`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        stubAccountsExist()
        val saved = stubSave()

        val result = service.execute(command())
        assertTrue(result.isSuccess)

        val tx = saved.first()
        val entry = WebhookOutboxEntry.transactionCommitted(tx)
        assertEquals("transaction.committed", entry.eventType)
        assertEquals(tx.id, entry.transactionId)
        assertEquals(tx.tenantId, entry.tenantId)
        assertEquals(tx.occurredAt, entry.occurredAt)
    }
}
