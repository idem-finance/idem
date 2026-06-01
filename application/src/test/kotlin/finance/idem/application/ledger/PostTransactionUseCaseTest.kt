package finance.idem.application.ledger

import finance.idem.application.audit.AuditEntry
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
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountRepository
import finance.idem.core.ledger.AccountType
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class PostTransactionUseCaseTest {

    @Mock lateinit var transactionRepository: TransactionRepository
    @Mock lateinit var accountRepository: AccountRepository
    @Mock lateinit var auditRepository: AuditRepository
    @Mock lateinit var webhookOutboxRepository: WebhookOutboxRepository
    @Mock lateinit var idempotencyStore: IdempotencyStore

    private lateinit var useCase: PostTransactionUseCase

    private val tenantId = TenantId.generate()
    private val debitAccountId = AccountId.generate()
    private val creditAccountId = AccountId.generate()

    @BeforeEach
    fun setUp() {
        useCase = PostTransactionUseCase(
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
        whenever(idempotencyStore.find(any(), any())).thenReturn(null)
        stubAccountsExist()
        val saved = stubSave()

        val result = useCase.execute(command())

        assertTrue(result.isSuccess)
        assertNotNull(result.getOrNull())
        assertEquals(saved.first().id, result.getOrNull())
    }

    @Test
    fun `all four writes are performed on success`() {
        whenever(idempotencyStore.find(any(), any())).thenReturn(null)
        stubAccountsExist()
        stubSave()

        useCase.execute(command())

        verify(transactionRepository).save(any())
        verify(auditRepository).save(any<AuditEntry>())
        verify(webhookOutboxRepository).save(any<WebhookOutboxEntry>())
        verify(idempotencyStore).record(any(), any(), any())
    }

    @Test
    fun `saved transaction has PENDING status`() {
        whenever(idempotencyStore.find(any(), any())).thenReturn(null)
        stubAccountsExist()
        val saved = stubSave()

        useCase.execute(command())

        assertEquals(TransactionStatus.PENDING, saved.first().status)
    }

    @Test
    fun `saved transaction carries correct tenant and idempotency key`() {
        whenever(idempotencyStore.find(any(), any())).thenReturn(null)
        stubAccountsExist()
        val saved = stubSave()

        useCase.execute(command(idempotencyKey = "my-key-42"))

        assertEquals(tenantId, saved.first().tenantId)
        assertEquals("my-key-42", saved.first().idempotencyKey)
    }

    // ── Idempotency ───────────────────────────────────────────────────────────

    @Test
    fun `returns existing TransactionId when key already committed`() {
        val existingId = TransactionId.generate()
        val existingTx = Transaction.create(
            id = existingId,
            tenantId = tenantId,
            idempotencyKey = "idem-001",
            lines = listOf(
                finance.idem.core.ledger.JournalLine(
                    id = java.util.UUID.randomUUID(),
                    transactionId = existingId,
                    accountId = debitAccountId,
                    tenantId = tenantId,
                    entryType = EntryType.DEBIT,
                    monetaryEntry = MonetaryEntry.FiatEntry(MonetaryAmount.of("500"), FiatCurrency.BRL, PaymentRail.PIX),
                    createdAt = Instant.now(),
                    createdBy = "system",
                ),
                finance.idem.core.ledger.JournalLine(
                    id = java.util.UUID.randomUUID(),
                    transactionId = existingId,
                    accountId = creditAccountId,
                    tenantId = tenantId,
                    entryType = EntryType.CREDIT,
                    monetaryEntry = MonetaryEntry.FiatEntry(MonetaryAmount.of("500"), FiatCurrency.BRL, PaymentRail.PIX),
                    createdAt = Instant.now(),
                    createdBy = "system",
                ),
            ),
            occurredAt = Instant.now(),
            createdAt = Instant.now(),
            createdBy = "system",
        ).copy(status = TransactionStatus.COMMITTED)

        whenever(idempotencyStore.find("idem-001", tenantId)).thenReturn(existingId)
        whenever(transactionRepository.findById(existingId, tenantId)).thenReturn(existingTx)

        val result = useCase.execute(command())

        assertTrue(result.isSuccess)
        assertEquals(existingId, result.getOrNull())
        verify(transactionRepository, never()).save(any())
        verify(auditRepository, never()).save(any())
    }

    @Test
    fun `returns IdempotencyConflict when key exists but transaction is PENDING`() {
        val existingId = TransactionId.generate()
        val pendingTx = Transaction.create(
            id = existingId,
            tenantId = tenantId,
            idempotencyKey = "idem-001",
            lines = listOf(
                finance.idem.core.ledger.JournalLine(
                    id = java.util.UUID.randomUUID(),
                    transactionId = existingId,
                    accountId = debitAccountId,
                    tenantId = tenantId,
                    entryType = EntryType.DEBIT,
                    monetaryEntry = MonetaryEntry.FiatEntry(MonetaryAmount.of("500"), FiatCurrency.BRL, PaymentRail.PIX),
                    createdAt = Instant.now(),
                    createdBy = "system",
                ),
                finance.idem.core.ledger.JournalLine(
                    id = java.util.UUID.randomUUID(),
                    transactionId = existingId,
                    accountId = creditAccountId,
                    tenantId = tenantId,
                    entryType = EntryType.CREDIT,
                    monetaryEntry = MonetaryEntry.FiatEntry(MonetaryAmount.of("500"), FiatCurrency.BRL, PaymentRail.PIX),
                    createdAt = Instant.now(),
                    createdBy = "system",
                ),
            ),
            occurredAt = Instant.now(),
            createdAt = Instant.now(),
            createdBy = "system",
        )

        whenever(idempotencyStore.find("idem-001", tenantId)).thenReturn(existingId)
        whenever(transactionRepository.findById(existingId, tenantId)).thenReturn(pendingTx)

        val result = useCase.execute(command())

        assertTrue(result.isFailure)
        assertIs<PostTransactionError.IdempotencyConflict>(result.exceptionOrNull())
    }

    // ── Account validation ────────────────────────────────────────────────────

    @Test
    fun `returns AccountNotFound when an account does not exist`() {
        whenever(idempotencyStore.find(any(), any())).thenReturn(null)
        // Only credit account exists — debit account is missing
        whenever(accountRepository.findExistingIds(any(), any()))
            .thenReturn(setOf(creditAccountId))

        val result = useCase.execute(command())

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertIs<PostTransactionError.AccountNotFound>(error)
        assertEquals(debitAccountId, error.accountId)
        verify(transactionRepository, never()).save(any())
    }

    // ── Double-entry invariant ────────────────────────────────────────────────

    @Test
    fun `returns InvariantViolation for unbalanced lines`() {
        whenever(idempotencyStore.find(any(), any())).thenReturn(null)
        whenever(accountRepository.findExistingIds(any(), any())).thenAnswer { inv ->
            inv.getArgument<Set<*>>(0).toSet()
        }

        val result = useCase.execute(command(lines = listOf(
            JournalLineRequest(
                accountId = debitAccountId,
                entryType = EntryType.DEBIT,
                monetaryEntry = MonetaryEntry.FiatEntry(MonetaryAmount.of("1000"), FiatCurrency.BRL, PaymentRail.PIX),
            ),
            JournalLineRequest(
                accountId = creditAccountId,
                entryType = EntryType.CREDIT,
                monetaryEntry = MonetaryEntry.FiatEntry(MonetaryAmount.of("999"), FiatCurrency.BRL, PaymentRail.PIX),
            ),
        )))

        assertTrue(result.isFailure)
        assertIs<PostTransactionError.InvariantViolation>(result.exceptionOrNull())
        verify(transactionRepository, never()).save(any())
    }

    @Test
    fun `returns InvariantViolation for single line`() {
        whenever(idempotencyStore.find(any(), any())).thenReturn(null)
        whenever(accountRepository.findExistingIds(any(), any())).thenAnswer { inv ->
            inv.getArgument<Set<*>>(0).toSet()
        }

        val result = useCase.execute(command(lines = listOf(
            brlLine(debitAccountId, EntryType.DEBIT),
        )))

        assertTrue(result.isFailure)
        val error = assertIs<PostTransactionError.InvariantViolation>(result.exceptionOrNull())
        assertNotNull(error.message)
    }

    @Test
    fun `AuditEntry saved with correct transaction and actor fields`() {
        whenever(idempotencyStore.find(any(), any())).thenReturn(null)
        stubAccountsExist()
        stubSave()
        val captor = argumentCaptor<AuditEntry>()

        useCase.execute(command())

        verify(auditRepository).save(captor.capture())
        val entry = captor.firstValue
        assertEquals(tenantId, entry.tenantId)
        assertEquals("POST_TRANSACTION", entry.action)
        assertEquals("sk_live_xxxx", entry.createdBy)
        assertNotNull(entry.transactionId)
    }

    @Test
    fun `InvariantViolation detail is accessible`() {
        whenever(idempotencyStore.find(any(), any())).thenReturn(null)
        whenever(accountRepository.findExistingIds(any(), any())).thenAnswer { inv ->
            inv.getArgument<Set<*>>(0).toSet()
        }

        val result = useCase.execute(command(lines = listOf(
            brlLine(debitAccountId, EntryType.DEBIT),
        )))

        val error = assertIs<PostTransactionError.InvariantViolation>(result.exceptionOrNull())
        assertNotNull(error.detail)
        assertEquals(error.detail, error.message)
    }

    @Test
    fun `WebhookOutboxEntry transactionCommitted carries correct fields`() {
        whenever(idempotencyStore.find(any(), any())).thenReturn(null)
        stubAccountsExist()
        val saved = stubSave()

        val result = useCase.execute(command())
        assertTrue(result.isSuccess)

        val tx = saved.first()
        val entry = WebhookOutboxEntry.transactionCommitted(tx)
        assertEquals("transaction.committed", entry.eventType)
        assertEquals(tx.id, entry.transactionId)
        assertEquals(tx.tenantId, entry.tenantId)
        assertEquals(tx.occurredAt, entry.occurredAt)
    }
}
