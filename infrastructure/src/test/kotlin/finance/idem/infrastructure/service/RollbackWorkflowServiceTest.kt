package finance.idem.infrastructure.service

import finance.idem.application.agentic.RollbackWorkflowCommand
import finance.idem.application.agentic.WorkflowPlanNotFound
import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.application.port.AgentAuditRepository
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.application.port.WorkflowPlanRepository
import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.AgentAuditEvent
import finance.idem.core.agentic.AgentAuditStatus
import finance.idem.core.agentic.AgentContext
import finance.idem.core.agentic.WorkflowPlan
import finance.idem.core.agentic.WorkflowPlanStatus
import finance.idem.core.ledger.JournalLine
import finance.idem.core.ledger.Transaction
import finance.idem.core.ledger.TransactionRepository
import finance.idem.core.monetary.FiatEntry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class RollbackWorkflowServiceTest {

    @Mock lateinit var workflowPlanRepository: WorkflowPlanRepository
    @Mock lateinit var agentAuditRepository: AgentAuditRepository
    @Mock lateinit var webhookOutboxRepository: WebhookOutboxRepository
    @Mock lateinit var transactionRepository: TransactionRepository
    @Mock lateinit var postTransactionUseCase: PostTransactionUseCase

    private lateinit var service: RollbackWorkflowService

    private val tenantId = TenantId.generate()
    private val planId = WorkflowPlanId.generate()
    private val agentContext = AgentContext(agentId = "agent-1", sessionId = "sess-1")
    private val now = Instant.now()
    private val debitAccountId = AccountId.generate()
    private val creditAccountId = AccountId.generate()

    @BeforeEach
    fun setUp() {
        service = RollbackWorkflowService(
            workflowPlanRepository,
            agentAuditRepository,
            webhookOutboxRepository,
            transactionRepository,
            postTransactionUseCase,
        )
    }

    private fun brlEntry() = FiatEntry(MonetaryAmount.of("500"), FiatCurrency.BRL, PaymentRail.PIX)

    private fun originalTx(txId: TransactionId): Transaction {
        val line = { id: AccountId, type: EntryType ->
            JournalLine(UUID.randomUUID(), txId, id, tenantId, type, brlEntry(), null, now, "system")
        }
        return Transaction.create(
            id = txId, tenantId = tenantId, idempotencyKey = "orig-${txId.value}",
            lines = listOf(line(debitAccountId, EntryType.DEBIT), line(creditAccountId, EntryType.CREDIT)),
            occurredAt = now, createdAt = now, createdBy = "system",
        )
    }

    private fun committedPlanWithSteps(tx0Id: TransactionId, tx1Id: TransactionId): WorkflowPlan =
        WorkflowPlan.create(
            id = planId, tenantId = tenantId, agentContext = agentContext,
            stepIdempotencyKeys = listOf("step-0", "step-1"),
            occurredAt = now,
        )
            .withStatus(WorkflowPlanStatus.EXECUTING)
            .withStepExecuted(0, tx0Id)
            .withStepExecuted(1, tx1Id)
            .withStatus(WorkflowPlanStatus.COMMITTED)
            .copy(committedAt = now)

    private fun rollbackCommand() = RollbackWorkflowCommand(
        tenantId = tenantId,
        agentContext = agentContext,
        workflowPlanId = planId,
        reason = "compliance review",
        createdBy = "sk_agent_test",
    )

    @Test
    fun `happy path — two steps rolled back in reverse order`() {
        val tx0Id = TransactionId.generate()
        val tx1Id = TransactionId.generate()
        val plan = committedPlanWithSteps(tx0Id, tx1Id)

        whenever(workflowPlanRepository.findById(planId, tenantId)).thenReturn(plan)
        whenever(transactionRepository.findById(tx0Id, tenantId)).thenReturn(originalTx(tx0Id))
        whenever(transactionRepository.findById(tx1Id, tenantId)).thenReturn(originalTx(tx1Id))
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.success(TransactionId.generate()))

        val result = service.execute(rollbackCommand())

        assertTrue(result.isSuccess)

        val postCaptor = argumentCaptor<finance.idem.application.ledger.PostTransactionCommand>()
        verify(postTransactionUseCase, times(2)).execute(postCaptor.capture())

        // Reverse order: step 1 before step 0
        assertEquals("rollback:${tx1Id.value}", postCaptor.allValues[0].idempotencyKey)
        assertEquals("rollback:${tx0Id.value}", postCaptor.allValues[1].idempotencyKey)
    }

    @Test
    fun `compensating lines swap DEBIT to CREDIT and vice versa`() {
        val txId = TransactionId.generate()
        val plan = WorkflowPlan.create(planId, tenantId, agentContext, listOf("step-0"), now)
            .withStatus(WorkflowPlanStatus.EXECUTING)
            .withStepExecuted(0, txId)
            .withStatus(WorkflowPlanStatus.COMMITTED)

        whenever(workflowPlanRepository.findById(planId, tenantId)).thenReturn(plan)
        whenever(transactionRepository.findById(txId, tenantId)).thenReturn(originalTx(txId))
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.success(TransactionId.generate()))

        service.execute(rollbackCommand())

        val captor = argumentCaptor<finance.idem.application.ledger.PostTransactionCommand>()
        verify(postTransactionUseCase).execute(captor.capture())
        val lines = captor.firstValue.lines

        // Original: DEBIT + CREDIT → compensating: CREDIT + DEBIT
        assertEquals(EntryType.CREDIT, lines.first { it.accountId == debitAccountId }.entryType)
        assertEquals(EntryType.DEBIT, lines.first { it.accountId == creditAccountId }.entryType)
    }

    @Test
    fun `returns WorkflowPlanNotFound when plan does not exist — no audit written`() {
        whenever(workflowPlanRepository.findById(planId, tenantId)).thenReturn(null)

        val result = service.execute(rollbackCommand())

        assertTrue(result.isFailure)
        assertIs<WorkflowPlanNotFound>(result.exceptionOrNull())
        verify(postTransactionUseCase, times(0)).execute(any())
        verify(agentAuditRepository, times(0)).save(any())
    }

    @Test
    fun `returns failure when plan is not COMMITTED — no audit or compensating transactions written`() {
        for (nonCommittedStatus in listOf(WorkflowPlanStatus.PLANNED, WorkflowPlanStatus.EXECUTING, WorkflowPlanStatus.ROLLED_BACK)) {
            val plan = WorkflowPlan.create(planId, tenantId, agentContext, emptyList(), now)
                .withStatus(nonCommittedStatus)
            whenever(workflowPlanRepository.findById(planId, tenantId)).thenReturn(plan)

            val result = service.execute(rollbackCommand())

            assertTrue(result.isFailure, "Expected failure for status $nonCommittedStatus")
            assertIs<IllegalStateException>(result.exceptionOrNull())
            assertTrue(result.exceptionOrNull()!!.message!!.contains(nonCommittedStatus.name))
        }
        verify(agentAuditRepository, times(0)).save(any())
        verify(postTransactionUseCase, times(0)).execute(any())
    }

    @Test
    fun `writes PENDING audit before rollback and COMPLETED after`() {
        val txId = TransactionId.generate()
        val plan = WorkflowPlan.create(planId, tenantId, agentContext, listOf("step-0"), now)
            .withStepExecuted(0, txId)
            .withStatus(WorkflowPlanStatus.COMMITTED)

        whenever(workflowPlanRepository.findById(planId, tenantId)).thenReturn(plan)
        whenever(transactionRepository.findById(txId, tenantId)).thenReturn(originalTx(txId))
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.success(TransactionId.generate()))

        service.execute(rollbackCommand())

        val auditCaptor = argumentCaptor<AgentAuditEvent>()
        verify(agentAuditRepository, times(2)).save(auditCaptor.capture())
        assertEquals(AgentAuditStatus.PENDING, auditCaptor.allValues[0].status)
        assertEquals(AgentAuditStatus.COMPLETED, auditCaptor.allValues[1].status)
        assertTrue(auditCaptor.allValues[1].outcome!!.contains("compliance review"))
    }

    @Test
    fun `outbox entry has workflow_rolled_back eventType`() {
        val plan = WorkflowPlan.create(planId, tenantId, agentContext, emptyList(), now)
            .withStatus(WorkflowPlanStatus.COMMITTED)

        whenever(workflowPlanRepository.findById(planId, tenantId)).thenReturn(plan)

        service.execute(rollbackCommand())

        val captor = argumentCaptor<WebhookOutboxEntry>()
        verify(webhookOutboxRepository).save(captor.capture())
        assertEquals("workflow.rolled_back", captor.firstValue.eventType)
        assertEquals(planId.value, captor.firstValue.transactionId.value)
    }

    @Test
    fun `plan status becomes ROLLED_BACK`() {
        val plan = WorkflowPlan.create(planId, tenantId, agentContext, emptyList(), now)
            .withStatus(WorkflowPlanStatus.COMMITTED)

        whenever(workflowPlanRepository.findById(planId, tenantId)).thenReturn(plan)

        service.execute(rollbackCommand())

        val statusCaptor = argumentCaptor<WorkflowPlanStatus>()
        verify(workflowPlanRepository, times(1)).updateStatus(any(), any(), statusCaptor.capture(), org.mockito.kotlin.anyOrNull())
        assertEquals(WorkflowPlanStatus.ROLLED_BACK, statusCaptor.firstValue)
    }

    @Test
    fun `compensating transaction failure propagates as RuntimeException`() {
        val txId = TransactionId.generate()
        val plan = WorkflowPlan.create(planId, tenantId, agentContext, listOf("step-0"), now)
            .withStepExecuted(0, txId)
            .withStatus(WorkflowPlanStatus.COMMITTED)

        whenever(workflowPlanRepository.findById(planId, tenantId)).thenReturn(plan)
        whenever(transactionRepository.findById(txId, tenantId)).thenReturn(originalTx(txId))
        whenever(postTransactionUseCase.execute(any()))
            .thenReturn(Result.failure(RuntimeException("ledger rejected")))

        val ex = org.junit.jupiter.api.assertThrows<RuntimeException> {
            service.execute(rollbackCommand())
        }
        assertTrue(ex.message!!.contains("step 0"))
        // No outbox entry written — compensating tx failed, outer @Transactional rolls back
        verify(webhookOutboxRepository, times(0)).save(any())
    }
}
