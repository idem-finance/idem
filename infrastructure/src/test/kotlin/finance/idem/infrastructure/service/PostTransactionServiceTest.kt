package finance.idem.infrastructure.service

import finance.idem.application.audit.AuditEntry
import finance.idem.application.compliance.TravelRuleValidationResult
import finance.idem.application.compliance.TravelRuleValidator
import finance.idem.application.ledger.IdempotencyConflict
import finance.idem.application.ledger.InvariantViolation
import finance.idem.application.ledger.JournalLineRequest
import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.application.ledger.PostTransactionError
import finance.idem.application.ledger.TransactionAccountNotFound
import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.application.port.AuditRepository
import finance.idem.application.port.ComplianceQueueRepository
import finance.idem.application.port.IdempotencyStore
import finance.idem.application.port.LgpdRetentionRepository
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.application.reconciliation.BasicReconciliationUseCase
import finance.idem.application.reconciliation.ReconciliationResult
import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.compliance.LegalPerson
import finance.idem.core.compliance.TravelRuleData
import finance.idem.core.compliance.VaspTransferParty
import finance.idem.core.ledger.AccountRepository
import finance.idem.core.ledger.JournalLine
import finance.idem.core.ledger.Transaction
import finance.idem.core.ledger.TransactionRepository
import finance.idem.core.ledger.TransactionStatus
import finance.idem.core.monetary.FiatEntry
import finance.idem.core.monetary.OnChainEntry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.inOrder
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

    @Mock lateinit var reconciliationService: BasicReconciliationUseCase

    @Mock lateinit var travelRuleValidator: TravelRuleValidator

    @Mock lateinit var complianceQueueRepository: ComplianceQueueRepository

    @Mock lateinit var lgpdRetentionRepository: LgpdRetentionRepository

    private lateinit var service: PostTransactionService

    private val tenantId = TenantId.generate()
    private val debitAccountId = AccountId.generate()
    private val creditAccountId = AccountId.generate()

    @BeforeEach
    fun setUp() {
        service =
            PostTransactionService(
                transactionRepository,
                accountRepository,
                auditRepository,
                webhookOutboxRepository,
                idempotencyStore,
                reconciliationService,
                travelRuleValidator,
                complianceQueueRepository,
                lgpdRetentionRepository,
            )
    }

    private fun brlLine(
        accountId: AccountId,
        entryType: EntryType,
    ) = JournalLineRequest(
        accountId = accountId,
        entryType = entryType,
        monetaryEntry =
            FiatEntry(
                amount = MonetaryAmount.of("1000.00"),
                currency = FiatCurrency.BRL,
                rail = PaymentRail.PIX,
            ),
    )

    private fun command(
        idempotencyKey: String = "idem-001",
        lines: List<JournalLineRequest> =
            listOf(
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
        // lenient: the compensating_for-metadata tests intentionally never reach this call.
        org.mockito.Mockito
            .lenient()
            .`when`(reconciliationService.reconcile(any()))
            .thenReturn(ReconciliationResult.NotApplicable)
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
        val existingTx =
            Transaction
                .create(
                    id = existingId,
                    tenantId = tenantId,
                    idempotencyKey = "idem-001",
                    lines =
                        listOf(
                            JournalLine(
                                UUID.randomUUID(),
                                existingId,
                                debitAccountId,
                                tenantId,
                                EntryType.DEBIT,
                                FiatEntry(MonetaryAmount.of("500"), FiatCurrency.BRL, PaymentRail.PIX),
                                null,
                                Instant.now(),
                                "system",
                            ),
                            JournalLine(
                                UUID.randomUUID(),
                                existingId,
                                creditAccountId,
                                tenantId,
                                EntryType.CREDIT,
                                FiatEntry(MonetaryAmount.of("500"), FiatCurrency.BRL, PaymentRail.PIX),
                                null,
                                Instant.now(),
                                "system",
                            ),
                        ),
                    occurredAt = Instant.now(),
                    createdAt = Instant.now(),
                    createdBy = "system",
                ).copy(status = TransactionStatus.COMMITTED)

        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(false)
        whenever(idempotencyStore.find("idem-001", tenantId)).thenReturn(existingId)
        whenever(transactionRepository.findById(existingId, tenantId)).thenReturn(existingTx)

        val result = service.execute(command())

        assertTrue(result.isSuccess)
        assertEquals(existingId, result.getOrNull())
        verify(transactionRepository, never()).save(any())
        verify(auditRepository, never()).save(any())
        verify(reconciliationService, never()).reconcile(any())
    }

    @Test
    fun `returns IdempotencyConflict when key exists but transaction is PENDING`() {
        val existingId = TransactionId.generate()
        val pendingTx =
            Transaction
                .create(
                    id = existingId,
                    tenantId = tenantId,
                    idempotencyKey = "idem-001",
                    lines =
                        listOf(
                            JournalLine(
                                UUID.randomUUID(),
                                existingId,
                                debitAccountId,
                                tenantId,
                                EntryType.DEBIT,
                                FiatEntry(MonetaryAmount.of("500"), FiatCurrency.BRL, PaymentRail.PIX),
                                null,
                                Instant.now(),
                                "system",
                            ),
                            JournalLine(
                                UUID.randomUUID(),
                                existingId,
                                creditAccountId,
                                tenantId,
                                EntryType.CREDIT,
                                FiatEntry(MonetaryAmount.of("500"), FiatCurrency.BRL, PaymentRail.PIX),
                                null,
                                Instant.now(),
                                "system",
                            ),
                        ),
                    occurredAt = Instant.now(),
                    createdAt = Instant.now(),
                    createdBy = "system",
                ).copy(status = TransactionStatus.PENDING)

        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(false)
        whenever(idempotencyStore.find("idem-001", tenantId)).thenReturn(existingId)
        whenever(transactionRepository.findById(existingId, tenantId)).thenReturn(pendingTx)

        val result = service.execute(command())

        assertTrue(result.isFailure)
        assertIs<IdempotencyConflict>(result.exceptionOrNull())
        verify(reconciliationService, never()).reconcile(any())
    }

    @Test
    fun `proceeds when previous transaction was ROLLED_BACK — releases and retries key`() {
        val rolledBackId = TransactionId.generate()
        val rolledBackTx =
            Transaction
                .create(
                    id = rolledBackId,
                    tenantId = tenantId,
                    idempotencyKey = "idem-001",
                    lines =
                        listOf(
                            JournalLine(
                                UUID.randomUUID(),
                                rolledBackId,
                                debitAccountId,
                                tenantId,
                                EntryType.DEBIT,
                                FiatEntry(MonetaryAmount.of("500"), FiatCurrency.BRL, PaymentRail.PIX),
                                null,
                                Instant.now(),
                                "system",
                            ),
                            JournalLine(
                                UUID.randomUUID(),
                                rolledBackId,
                                creditAccountId,
                                tenantId,
                                EntryType.CREDIT,
                                FiatEntry(MonetaryAmount.of("500"), FiatCurrency.BRL, PaymentRail.PIX),
                                null,
                                Instant.now(),
                                "system",
                            ),
                        ),
                    occurredAt = Instant.now(),
                    createdAt = Instant.now(),
                    createdBy = "system",
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
        assertIs<TransactionAccountNotFound>(error)
        assertEquals(debitAccountId, error.accountId)
        verify(transactionRepository, never()).save(any())
        verify(reconciliationService, never()).reconcile(any())
    }

    // ── Double-entry invariant ────────────────────────────────────────────────

    @Test
    fun `returns InvariantViolation for unbalanced lines`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        whenever(accountRepository.findExistingIds(any(), any())).thenAnswer { inv ->
            inv.getArgument<Set<*>>(0).toSet()
        }

        val result =
            service.execute(
                command(
                    lines =
                        listOf(
                            JournalLineRequest(
                                debitAccountId,
                                EntryType.DEBIT,
                                FiatEntry(MonetaryAmount.of("1000"), FiatCurrency.BRL, PaymentRail.PIX),
                            ),
                            JournalLineRequest(
                                creditAccountId,
                                EntryType.CREDIT,
                                FiatEntry(MonetaryAmount.of("999"), FiatCurrency.BRL, PaymentRail.PIX),
                            ),
                        ),
                ),
            )

        assertTrue(result.isFailure)
        assertIs<InvariantViolation>(result.exceptionOrNull())
        verify(transactionRepository, never()).save(any())
        verify(reconciliationService, never()).reconcile(any())
    }

    @Test
    fun `returns InvariantViolation for single line`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        whenever(accountRepository.findExistingIds(any(), any())).thenAnswer { inv ->
            inv.getArgument<Set<*>>(0).toSet()
        }

        val result = service.execute(command(lines = listOf(brlLine(debitAccountId, EntryType.DEBIT))))

        assertTrue(result.isFailure)
        val error = assertIs<InvariantViolation>(result.exceptionOrNull())
        assertNotNull(error.message)
        verify(reconciliationService, never()).reconcile(any())
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

        val error = assertIs<InvariantViolation>(result.exceptionOrNull())
        assertNotNull(error.detail)
        assertEquals(error.detail, error.message)
        verify(reconciliationService, never()).reconcile(any())
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

    // ── Reconciliation ────────────────────────────────────────────────────────

    @Test
    fun `reconcile is called with the persisted transaction`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        stubAccountsExist()
        val saved = stubSave()

        service.execute(command())

        val captor = argumentCaptor<Transaction>()
        verify(reconciliationService).reconcile(captor.capture())
        assertEquals(saved.first().id, captor.firstValue.id)
    }

    @Test
    fun `reconcile is skipped when transaction carries compensating_for metadata`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        stubAccountsExist()
        stubSave()

        val compensatingCmd =
            command().copy(metadata = mapOf("compensating_for" to TransactionId.generate().value.toString()))
        val result = service.execute(compensatingCmd)

        assertTrue(result.isSuccess)
        verify(reconciliationService, never()).reconcile(any())
    }

    @Test
    fun `reconcile still runs for non-compensating transactions with other metadata`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        stubAccountsExist()
        stubSave()

        val cmd = command().copy(metadata = mapOf("chain_key" to "EVM_1"))
        val result = service.execute(cmd)

        assertTrue(result.isSuccess)
        verify(reconciliationService).reconcile(any())
    }

    @Test
    fun `audit is written before transaction, webhook last before reconcile`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        stubAccountsExist()
        stubSave()

        service.execute(command())

        val order = inOrder(auditRepository, transactionRepository, webhookOutboxRepository, reconciliationService)
        order.verify(auditRepository).save(any())
        order.verify(transactionRepository).save(any())
        order.verify(webhookOutboxRepository).save(any())
        order.verify(reconciliationService).reconcile(any())
    }

    // ── Travel Rule compliance ────────────────────────────────────────────────

    private fun onChainLine(
        accountId: AccountId,
        entryType: EntryType,
        amount: String = "1500.00",
    ) = JournalLineRequest(
        accountId = accountId,
        entryType = entryType,
        monetaryEntry =
            OnChainEntry(
                amount = MonetaryAmount.of(amount),
                token = StablecoinToken.USDC,
                chainId = ChainId.EVM,
                txHash = "0xabc123",
                blockNumber = 100L,
                walletAddress = "0xwallet",
                tokenContract = "0xcontract",
            ),
    )

    @Test
    fun `FiatEntry lines skip travel rule validation entirely`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        stubAccountsExist()
        stubSave()

        service.execute(command())

        verify(travelRuleValidator, never()).validate(any(), anyOrNull())
        verify(complianceQueueRepository, never()).enqueue(any())
    }

    @Test
    fun `OnChainEntry below threshold does not write to compliance queue`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        whenever(accountRepository.findExistingIds(any(), any()))
            .thenReturn(setOf(debitAccountId, creditAccountId))
        stubSave()

        val debitLine = onChainLine(debitAccountId, EntryType.DEBIT, "500.00")
        val creditLine = onChainLine(creditAccountId, EntryType.CREDIT, "500.00")
        whenever(travelRuleValidator.validate(any(), anyOrNull()))
            .thenReturn(TravelRuleValidationResult.Exempt)

        val result = service.execute(command(lines = listOf(debitLine, creditLine)))

        assertTrue(result.isSuccess)
        verify(complianceQueueRepository, never()).enqueue(any())
    }

    @Test
    fun `OnChainEntry with MissingData writes to compliance queue and fires webhook`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        whenever(accountRepository.findExistingIds(any(), any()))
            .thenReturn(setOf(debitAccountId, creditAccountId))
        stubSave()

        val debitLine = onChainLine(debitAccountId, EntryType.DEBIT)
        val creditLine = onChainLine(creditAccountId, EntryType.CREDIT)
        val missingResult =
            TravelRuleValidationResult.MissingData(
                entry = debitLine.monetaryEntry as OnChainEntry,
                reason = "Travel rule data required for transfers >= 1000",
            )
        whenever(travelRuleValidator.validate(any(), anyOrNull()))
            .thenReturn(missingResult)
            .thenReturn(TravelRuleValidationResult.Exempt)

        val result = service.execute(command(lines = listOf(debitLine, creditLine)))

        assertTrue(result.isSuccess)
        verify(complianceQueueRepository).enqueue(any())
        // Two webhooks: transaction.committed + compliance.travel_rule_required
        verify(webhookOutboxRepository, org.mockito.kotlin.times(2)).save(any())
    }

    @Test
    fun `two flagged OnChainEntry lines enqueue both items but fire only one compliance webhook`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        whenever(accountRepository.findExistingIds(any(), any()))
            .thenReturn(setOf(debitAccountId, creditAccountId))
        stubSave()

        val debitLine = onChainLine(debitAccountId, EntryType.DEBIT)
        val creditLine = onChainLine(creditAccountId, EntryType.CREDIT)
        val missingDebit =
            TravelRuleValidationResult.MissingData(
                entry = debitLine.monetaryEntry as OnChainEntry,
                reason = "Travel rule data required for transfers >= 1000",
            )
        val missingCredit =
            TravelRuleValidationResult.MissingData(
                entry = creditLine.monetaryEntry as OnChainEntry,
                reason = "Travel rule data required for transfers >= 1000",
            )
        whenever(travelRuleValidator.validate(any(), anyOrNull()))
            .thenReturn(missingDebit)
            .thenReturn(missingCredit)

        val result = service.execute(command(lines = listOf(debitLine, creditLine)))

        assertTrue(result.isSuccess)
        // Both flagged entries written to compliance queue
        verify(complianceQueueRepository, org.mockito.kotlin.times(2)).enqueue(any())
        // Exactly two outbox rows: transactionCommitted + one travelRuleRequired (not two)
        verify(webhookOutboxRepository, org.mockito.kotlin.times(2)).save(any())
    }

    @Test
    fun `OnChainEntry with Valid travelRuleData does not write to compliance queue`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        whenever(accountRepository.findExistingIds(any(), any()))
            .thenReturn(setOf(debitAccountId, creditAccountId))
        stubSave()

        val debitLine = onChainLine(debitAccountId, EntryType.DEBIT)
        val creditLine = onChainLine(creditAccountId, EntryType.CREDIT)
        val party =
            VaspTransferParty(
                legalPerson = LegalPerson(name = "Acme Corp", registrationNumber = "123", country = "US"),
                accountNumber = "0xabc",
                vaspDid = "did:example:acme",
            )
        val validData =
            TravelRuleData(
                transferId = "tx-valid-001",
                originator = party,
                beneficiary = party,
                transferAmount = MonetaryAmount.of("1500.00"),
                transferAsset = StablecoinToken.USDC,
                threshold = TravelRuleData.defaultThresholdFor(StablecoinToken.USDC),
            )
        whenever(travelRuleValidator.validate(any(), anyOrNull()))
            .thenReturn(TravelRuleValidationResult.Valid(validData))

        val result = service.execute(command(lines = listOf(debitLine, creditLine)))

        assertTrue(result.isSuccess)
        verify(complianceQueueRepository, never()).enqueue(any())
    }

    // ── LGPD retention scheduling ─────────────────────────────────────────────

    @Test
    fun `Valid TravelRule result schedules LGPD retention for each OnChain entry`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        whenever(accountRepository.findExistingIds(any(), any()))
            .thenReturn(setOf(debitAccountId, creditAccountId))
        stubSave()

        val debitLine = onChainLine(debitAccountId, EntryType.DEBIT)
        val creditLine = onChainLine(creditAccountId, EntryType.CREDIT)
        val party =
            VaspTransferParty(
                legalPerson = LegalPerson(name = "Acme Corp", registrationNumber = "123", country = "US"),
                accountNumber = "0xabc",
                vaspDid = "did:example:acme",
            )
        val validData =
            TravelRuleData(
                transferId = "tx-lgpd-001",
                originator = party,
                beneficiary = party,
                transferAmount = MonetaryAmount.of("1500.00"),
                transferAsset = StablecoinToken.USDC,
                threshold = TravelRuleData.defaultThresholdFor(StablecoinToken.USDC),
            )
        whenever(travelRuleValidator.validate(any(), anyOrNull()))
            .thenReturn(TravelRuleValidationResult.Valid(validData))

        val result = service.execute(command(lines = listOf(debitLine, creditLine)))

        assertTrue(result.isSuccess)
        // Both OnChain lines are Valid — one schedule() call per line
        val entityTypeCaptor = argumentCaptor<String>()
        val entityIdCaptor = argumentCaptor<String>()
        verify(lgpdRetentionRepository, org.mockito.kotlin.times(2))
            .schedule(any(), entityTypeCaptor.capture(), entityIdCaptor.capture(), any())
        entityTypeCaptor.allValues.forEach { assertEquals("TravelRuleData", it) }
        entityIdCaptor.allValues.forEach { assertEquals("tx-lgpd-001", it) }
    }

    @Test
    fun `Exempt TravelRule result does not schedule LGPD retention`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        whenever(accountRepository.findExistingIds(any(), any()))
            .thenReturn(setOf(debitAccountId, creditAccountId))
        stubSave()

        val debitLine = onChainLine(debitAccountId, EntryType.DEBIT)
        val creditLine = onChainLine(creditAccountId, EntryType.CREDIT)
        whenever(travelRuleValidator.validate(any(), anyOrNull()))
            .thenReturn(TravelRuleValidationResult.Exempt)

        val result = service.execute(command(lines = listOf(debitLine, creditLine)))

        assertTrue(result.isSuccess)
        verify(lgpdRetentionRepository, never()).schedule(any(), any(), any(), any())
    }

    @Test
    fun `MissingData TravelRule result does not schedule LGPD retention`() {
        whenever(idempotencyStore.tryRecord(any(), any(), any())).thenReturn(true)
        whenever(accountRepository.findExistingIds(any(), any()))
            .thenReturn(setOf(debitAccountId, creditAccountId))
        stubSave()

        val debitLine = onChainLine(debitAccountId, EntryType.DEBIT)
        val creditLine = onChainLine(creditAccountId, EntryType.CREDIT)
        val missingResult =
            TravelRuleValidationResult.MissingData(
                entry = debitLine.monetaryEntry as OnChainEntry,
                reason = "Travel rule data required",
            )
        whenever(travelRuleValidator.validate(any(), anyOrNull()))
            .thenReturn(missingResult)
            .thenReturn(TravelRuleValidationResult.Exempt)

        val result = service.execute(command(lines = listOf(debitLine, creditLine)))

        assertTrue(result.isSuccess)
        verify(lgpdRetentionRepository, never()).schedule(any(), any(), any(), any())
    }
}
