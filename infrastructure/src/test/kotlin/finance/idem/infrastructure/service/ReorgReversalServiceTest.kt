package finance.idem.infrastructure.service

import finance.idem.application.ledger.PostTransactionCommand
import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.application.port.AgentAuditRepository
import finance.idem.application.port.IdempotencyStore
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.application.reconciliation.ReorgReversalCommand
import finance.idem.application.reconciliation.ReorgReversalResult
import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.EntryType
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.AgentAuditEvent
import finance.idem.core.agentic.AgentContext
import finance.idem.core.agentic.StepStatus
import finance.idem.core.agentic.WorkflowPlan
import finance.idem.core.agentic.WorkflowPlanRepository
import finance.idem.core.agentic.WorkflowStep
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.JournalLine
import finance.idem.core.ledger.Settlement
import finance.idem.core.ledger.SettlementRepository
import finance.idem.core.ledger.Transaction
import finance.idem.core.ledger.TransactionRepository
import finance.idem.core.monetary.OnChainEntry
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
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class ReorgReversalServiceTest {
    @Mock lateinit var settlementRepository: SettlementRepository

    @Mock lateinit var transactionRepository: TransactionRepository

    @Mock lateinit var postTransactionUseCase: PostTransactionUseCase

    @Mock lateinit var webhookOutboxRepository: WebhookOutboxRepository

    @Mock lateinit var idempotencyStore: IdempotencyStore

    @Mock lateinit var workflowPlanRepository: WorkflowPlanRepository

    @Mock lateinit var agentAuditRepository: AgentAuditRepository

    private lateinit var service: ReorgReversalService

    private val tenantId = TenantId.generate()
    private val accountId = AccountId.generate()
    private val debitAccountId = AccountId.generate()
    private val creditAccountId = AccountId.generate()
    private val now = Instant.now()
    private val usdcMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    private val watchedWallet = "0xabcdef1234567890abcdef1234567890abcdef34"
    private val txHash = "0xabc123"

    @BeforeEach
    fun setUp() {
        service =
            ReorgReversalService(
                settlementRepository,
                transactionRepository,
                postTransactionUseCase,
                webhookOutboxRepository,
                idempotencyStore,
                workflowPlanRepository,
                agentAuditRepository,
            )
    }

    private fun cmd(reason: String = "alchemy webhook: removed=true") = ReorgReversalCommand(tenantId, txHash, 2, "EVM_1", reason)

    private fun onChainEntry() =
        OnChainEntry(
            amount = MonetaryAmount.of("100.000000"),
            token = StablecoinToken.USDC,
            chainId = ChainId.EVM,
            txHash = txHash,
            blockNumber = 100L,
            walletAddress = watchedWallet,
            tokenContract = usdcMint,
        )

    private fun originalTx(txId: TransactionId): Transaction {
        val entry = onChainEntry()
        return Transaction.create(
            id = txId,
            tenantId = tenantId,
            idempotencyKey = "EVM_1:$txHash:2",
            lines =
                listOf(
                    JournalLine(UUID.randomUUID(), txId, debitAccountId, tenantId, EntryType.DEBIT, entry, null, now, "alchemy-webhook"),
                    JournalLine(UUID.randomUUID(), txId, creditAccountId, tenantId, EntryType.CREDIT, entry, null, now, "alchemy-webhook"),
                ),
            occurredAt = now,
            createdAt = now,
            createdBy = "alchemy-webhook",
        )
    }

    private fun reversibleSettlement(
        matchedTransactionId: TransactionId,
        status: EntryStatus = EntryStatus.WATCHING,
    ) = Settlement(
        id = UUID.randomUUID(),
        tenantId = tenantId,
        accountId = accountId,
        amount = MonetaryAmount.of("100.000000"),
        token = StablecoinToken.USDC,
        chainId = ChainId.EVM,
        walletAddress = watchedWallet,
        status = status,
        matchedTransactionId = matchedTransactionId,
        txHash = txHash,
        blockNumber = 100L,
        chainKey = "EVM_1",
        logIndex = 2,
        createdAt = now,
        createdBy = "system",
    )

    @Test
    fun `returns NoMatchingSettlement when no reversible settlement exists`() {
        whenever(settlementRepository.findReversibleByTxHashAndLogIndex(tenantId, txHash, 2)).thenReturn(null)

        val result = service.execute(cmd()).getOrThrow()

        assertEquals(ReorgReversalResult.NoMatchingSettlement, result)
        verify(postTransactionUseCase, never()).execute(any())
        verify(settlementRepository, never()).markReorged(any(), any(), any(), any())
        verify(idempotencyStore, never()).release(any(), any())
    }

    @Test
    fun `returns AlreadyReorged defensively when the found row is already REORGED`() {
        val txId = TransactionId.generate()
        val alreadyReorged = reversibleSettlement(txId, status = EntryStatus.REORGED)
        whenever(settlementRepository.findReversibleByTxHashAndLogIndex(tenantId, txHash, 2)).thenReturn(alreadyReorged)

        val result = service.execute(cmd()).getOrThrow()

        assertEquals(ReorgReversalResult.AlreadyReorged, result)
        verify(postTransactionUseCase, never()).execute(any())
        verify(settlementRepository, never()).markReorged(any(), any(), any(), any())
    }

    @Test
    fun `original transaction not found returns failure`() {
        val txId = TransactionId.generate()
        whenever(settlementRepository.findReversibleByTxHashAndLogIndex(tenantId, txHash, 2))
            .thenReturn(reversibleSettlement(txId))
        whenever(transactionRepository.findById(txId, tenantId)).thenReturn(null)

        val result = service.execute(cmd())

        assertTrue(result.isFailure)
        verify(postTransactionUseCase, never()).execute(any())
    }

    @Test
    fun `successful reversal posts a compensating transaction, marks REORGED, releases idempotency key, writes outbox`() {
        val originalTxId = TransactionId.generate()
        val settlement = reversibleSettlement(originalTxId)
        val tx = originalTx(originalTxId)
        val compensatingTxId = TransactionId.generate()

        whenever(settlementRepository.findReversibleByTxHashAndLogIndex(tenantId, txHash, 2)).thenReturn(settlement)
        whenever(transactionRepository.findById(originalTxId, tenantId)).thenReturn(tx)
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.success(compensatingTxId))
        whenever(settlementRepository.markReorged(any(), any(), any(), any())).thenReturn(true)

        val result = service.execute(cmd()).getOrThrow()

        assertIs<ReorgReversalResult.Reversed>(result)
        assertEquals(compensatingTxId, result.reversalTransactionId)
        assertEquals(EntryStatus.REORGED, result.settlement.status)
        assertEquals(txHash, result.settlement.txHash) // original evidence untouched
        assertEquals(100L, result.settlement.blockNumber)

        val cmdCaptor = argumentCaptor<PostTransactionCommand>()
        verify(postTransactionUseCase).execute(cmdCaptor.capture())
        val compensatingCmd = cmdCaptor.firstValue
        assertEquals("reorg-reversal:${originalTxId.value}", compensatingCmd.idempotencyKey)
        assertEquals("chain-reorg-reversal", compensatingCmd.createdBy)
        assertEquals(2, compensatingCmd.lines.size)
        assertEquals(EntryType.CREDIT, compensatingCmd.lines.first { it.accountId == debitAccountId }.entryType)
        assertEquals(EntryType.DEBIT, compensatingCmd.lines.first { it.accountId == creditAccountId }.entryType)

        verify(settlementRepository).markReorged(any(), any(), any(), any())
        verify(idempotencyStore).release("EVM_1:$txHash:2", tenantId)

        val outboxCaptor = argumentCaptor<WebhookOutboxEntry>()
        verify(webhookOutboxRepository).save(outboxCaptor.capture())
        assertEquals("settlement.reorged", outboxCaptor.firstValue.eventType)
    }

    @Test
    fun `markReorged returning false (lost the race to a concurrent reversal) reports AlreadyReorged and skips the outbox write`() {
        val originalTxId = TransactionId.generate()
        val settlement = reversibleSettlement(originalTxId)
        val tx = originalTx(originalTxId)
        val compensatingTxId = TransactionId.generate()

        whenever(settlementRepository.findReversibleByTxHashAndLogIndex(tenantId, txHash, 2)).thenReturn(settlement)
        whenever(transactionRepository.findById(originalTxId, tenantId)).thenReturn(tx)
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.success(compensatingTxId))
        whenever(settlementRepository.markReorged(any(), any(), any(), any())).thenReturn(false)

        val result = service.execute(cmd()).getOrThrow()

        assertEquals(ReorgReversalResult.AlreadyReorged, result)
        verify(idempotencyStore, never()).release(any(), any())
        verify(webhookOutboxRepository, never()).save(any())
    }

    @Test
    fun `original transaction already compensated by rollback reuses that transaction — no duplicate post`() {
        val originalTxId = TransactionId.generate()
        val settlement = reversibleSettlement(originalTxId)
        val tx = originalTx(originalTxId)
        val rollbackTxId = TransactionId.generate()
        val rollbackTx = originalTx(rollbackTxId)

        whenever(settlementRepository.findReversibleByTxHashAndLogIndex(tenantId, txHash, 2)).thenReturn(settlement)
        whenever(transactionRepository.findById(originalTxId, tenantId)).thenReturn(tx)
        whenever(transactionRepository.findByIdempotencyKey("rollback:${originalTxId.value}", tenantId)).thenReturn(rollbackTx)
        whenever(settlementRepository.markReorged(any(), any(), any(), any())).thenReturn(true)

        val result = service.execute(cmd()).getOrThrow()

        assertIs<ReorgReversalResult.AlreadyCompensatedByRollback>(result)
        assertEquals(rollbackTxId, result.rollbackTransactionId)
        assertEquals(EntryStatus.REORGED, result.settlement.status)

        verify(postTransactionUseCase, never()).execute(any())
        verify(settlementRepository).markReorged(any(), any(), any(), any())
        verify(idempotencyStore).release("EVM_1:$txHash:2", tenantId)

        val outboxCaptor = argumentCaptor<WebhookOutboxEntry>()
        verify(webhookOutboxRepository).save(outboxCaptor.capture())
        assertEquals("settlement.reorged", outboxCaptor.firstValue.eventType)
    }

    @Test
    fun `no matching workflow plan for the transaction — reversal proceeds unchanged, no audit event`() {
        val originalTxId = TransactionId.generate()
        val settlement = reversibleSettlement(originalTxId)
        val tx = originalTx(originalTxId)
        val compensatingTxId = TransactionId.generate()

        whenever(settlementRepository.findReversibleByTxHashAndLogIndex(tenantId, txHash, 2)).thenReturn(settlement)
        whenever(transactionRepository.findById(originalTxId, tenantId)).thenReturn(tx)
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.success(compensatingTxId))
        whenever(settlementRepository.markReorged(any(), any(), any(), any())).thenReturn(true)
        whenever(workflowPlanRepository.findByTransactionId(originalTxId, tenantId)).thenReturn(null)

        val result = service.execute(cmd()).getOrThrow()

        assertIs<ReorgReversalResult.Reversed>(result)
        verify(workflowPlanRepository, never()).updateStep(any(), any(), any())
        verify(agentAuditRepository, never()).save(any())
    }

    @Test
    fun `matching agent workflow step transitions to REORGED and an audit event is written`() {
        val originalTxId = TransactionId.generate()
        val settlement = reversibleSettlement(originalTxId)
        val tx = originalTx(originalTxId)
        val compensatingTxId = TransactionId.generate()

        val agentContext = AgentContext(agentId = "agent-1", sessionId = "session-1")
        val plan =
            WorkflowPlan
                .create(
                    id = WorkflowPlanId.generate(),
                    tenantId = tenantId,
                    agentContext = agentContext,
                    stepDescriptions = listOf("disburse on-chain"),
                    createdAt = now,
                ).withStepExecuted(0, originalTxId)

        whenever(settlementRepository.findReversibleByTxHashAndLogIndex(tenantId, txHash, 2)).thenReturn(settlement)
        whenever(transactionRepository.findById(originalTxId, tenantId)).thenReturn(tx)
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.success(compensatingTxId))
        whenever(settlementRepository.markReorged(any(), any(), any(), any())).thenReturn(true)
        whenever(workflowPlanRepository.findByTransactionId(originalTxId, tenantId)).thenReturn(plan)

        val result = service.execute(cmd()).getOrThrow()

        assertIs<ReorgReversalResult.Reversed>(result)

        val stepCaptor = argumentCaptor<WorkflowStep>()
        verify(workflowPlanRepository).updateStep(any(), any(), stepCaptor.capture())
        assertEquals(StepStatus.REORGED, stepCaptor.firstValue.status)
        assertEquals(compensatingTxId, stepCaptor.firstValue.compensatingTransactionId)

        val eventCaptor = argumentCaptor<AgentAuditEvent>()
        verify(agentAuditRepository).save(eventCaptor.capture())
        assertEquals(plan.id, eventCaptor.firstValue.workflowPlanId)
        assertEquals("CHAIN_REORG_REVERSAL", eventCaptor.firstValue.intent)
    }

    @Test
    fun `failed compensating post leaves the settlement row and idempotency key untouched`() {
        val originalTxId = TransactionId.generate()
        val settlement = reversibleSettlement(originalTxId)
        val tx = originalTx(originalTxId)

        whenever(settlementRepository.findReversibleByTxHashAndLogIndex(tenantId, txHash, 2)).thenReturn(settlement)
        whenever(transactionRepository.findById(originalTxId, tenantId)).thenReturn(tx)
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.failure(RuntimeException("conflict")))

        val result = service.execute(cmd())

        assertTrue(result.isFailure)
        verify(settlementRepository, never()).markReorged(any(), any(), any(), any())
        verify(idempotencyStore, never()).release(any(), any())
        verify(webhookOutboxRepository, never()).save(any())
    }
}
