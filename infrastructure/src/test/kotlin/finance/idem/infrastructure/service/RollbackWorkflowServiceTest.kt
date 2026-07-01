package finance.idem.infrastructure.service

import finance.idem.application.agentic.RollbackWorkflowCommand
import finance.idem.application.agentic.WorkflowPlanNotFound
import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.application.outbox.WebhookOutboxEntry
import finance.idem.application.port.AgentAuditRepository
import finance.idem.application.port.WebhookOutboxRepository
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
import finance.idem.core.agentic.WorkflowPlanRepository
import finance.idem.core.agentic.WorkflowStatus
import finance.idem.core.ledger.JournalLine
import finance.idem.core.ledger.Transaction
import finance.idem.core.ledger.TransactionRepository
import finance.idem.core.monetary.FiatEntry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
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
        service =
            RollbackWorkflowService(
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
            id = txId,
            tenantId = tenantId,
            idempotencyKey = "orig-${txId.value}",
            lines = listOf(line(debitAccountId, EntryType.DEBIT), line(creditAccountId, EntryType.CREDIT)),
            occurredAt = now,
            createdAt = now,
            createdBy = "system",
        )
    }

    private fun committedPlanWithSteps(
        tx0Id: TransactionId,
        tx1Id: TransactionId,
    ): WorkflowPlan =
        WorkflowPlan
            .create(
                id = planId,
                tenantId = tenantId,
                agentContext = agentContext,
                stepDescriptions = listOf("step-0", "step-1"),
                createdAt = now,
            ).withStatus(WorkflowStatus.EXECUTING)
            .withStepExecuted(0, tx0Id)
            .withStepExecuted(1, tx1Id)
            .withStatus(WorkflowStatus.COMMITTED)
            .copy(completedAt = now)

    private fun rollbackCommand() =
        RollbackWorkflowCommand(
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
        val plan =
            WorkflowPlan
                .create(planId, tenantId, agentContext, listOf("step-0"), now)
                .withStatus(WorkflowStatus.EXECUTING)
                .withStepExecuted(0, txId)
                .withStatus(WorkflowStatus.COMMITTED)

        whenever(workflowPlanRepository.findById(planId, tenantId)).thenReturn(plan)
        whenever(transactionRepository.findById(txId, tenantId)).thenReturn(originalTx(txId))
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.success(TransactionId.generate()))

        service.execute(rollbackCommand())

        val captor = argumentCaptor<finance.idem.application.ledger.PostTransactionCommand>()
        verify(postTransactionUseCase).execute(captor.capture())
        val lines = captor.firstValue.lines

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
    fun `returns failure when plan is in a non-rollbackable status`() {
        for (status in listOf(WorkflowStatus.PLANNED, WorkflowStatus.FAILED, WorkflowStatus.ROLLED_BACK)) {
            val plan =
                WorkflowPlan
                    .create(planId, tenantId, agentContext, emptyList(), now)
                    .withStatus(status)
            whenever(workflowPlanRepository.findById(planId, tenantId)).thenReturn(plan)

            val result = service.execute(rollbackCommand())

            assertTrue(result.isFailure, "Expected failure for status $status")
            assertIs<IllegalStateException>(result.exceptionOrNull())
            assertTrue(result.exceptionOrNull()!!.message!!.contains(status.name))
        }
        verify(agentAuditRepository, times(0)).save(any())
        verify(postTransactionUseCase, times(0)).execute(any())
    }

    @Test
    fun `EXECUTING plan rolls back successfully`() {
        val txId = TransactionId.generate()
        val plan =
            WorkflowPlan
                .create(planId, tenantId, agentContext, listOf("step-0"), now)
                .withStepExecuted(0, txId)
                .withStatus(WorkflowStatus.EXECUTING)

        whenever(workflowPlanRepository.findById(planId, tenantId)).thenReturn(plan)
        whenever(transactionRepository.findById(txId, tenantId)).thenReturn(originalTx(txId))
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.success(TransactionId.generate()))

        assertTrue(service.execute(rollbackCommand()).isSuccess)
    }

    @Test
    fun `compensating transaction carries compensating_for metadata`() {
        val txId = TransactionId.generate()
        val plan =
            WorkflowPlan
                .create(planId, tenantId, agentContext, listOf("step-0"), now)
                .withStepExecuted(0, txId)
                .withStatus(WorkflowStatus.COMMITTED)

        whenever(workflowPlanRepository.findById(planId, tenantId)).thenReturn(plan)
        whenever(transactionRepository.findById(txId, tenantId)).thenReturn(originalTx(txId))
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.success(TransactionId.generate()))

        service.execute(rollbackCommand())

        val captor = argumentCaptor<finance.idem.application.ledger.PostTransactionCommand>()
        verify(postTransactionUseCase).execute(captor.capture())
        assertEquals(txId.value.toString(), captor.firstValue.metadata["compensating_for"])
    }

    @Test
    fun `each executed step is marked ROLLED_BACK via updateStep`() {
        val tx0Id = TransactionId.generate()
        val tx1Id = TransactionId.generate()
        val comp1Id = TransactionId.generate()
        val comp0Id = TransactionId.generate()
        val plan = committedPlanWithSteps(tx0Id, tx1Id)

        whenever(workflowPlanRepository.findById(planId, tenantId)).thenReturn(plan)
        whenever(transactionRepository.findById(tx0Id, tenantId)).thenReturn(originalTx(tx0Id))
        whenever(transactionRepository.findById(tx1Id, tenantId)).thenReturn(originalTx(tx1Id))
        // Reverse order: step 1 compensated first, then step 0
        whenever(postTransactionUseCase.execute(any()))
            .thenReturn(Result.success(comp1Id))
            .thenReturn(Result.success(comp0Id))

        service.execute(rollbackCommand())

        val stepCaptor = argumentCaptor<finance.idem.core.agentic.WorkflowStep>()
        verify(workflowPlanRepository, times(2)).updateStep(any(), any(), stepCaptor.capture())
        assertTrue(stepCaptor.allValues.all { it.status == finance.idem.core.agentic.StepStatus.ROLLED_BACK })
        assertTrue(stepCaptor.allValues.any { it.stepOrder == 1 && it.compensatingTransactionId == comp1Id })
        assertTrue(stepCaptor.allValues.any { it.stepOrder == 0 && it.compensatingTransactionId == comp0Id })
    }

    @Test
    fun `writes PENDING audit before rollback and COMPLETED after`() {
        val txId = TransactionId.generate()
        val plan =
            WorkflowPlan
                .create(planId, tenantId, agentContext, listOf("step-0"), now)
                .withStepExecuted(0, txId)
                .withStatus(WorkflowStatus.COMMITTED)

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
        val plan =
            WorkflowPlan
                .create(planId, tenantId, agentContext, emptyList(), now)
                .withStatus(WorkflowStatus.COMMITTED)

        whenever(workflowPlanRepository.findById(planId, tenantId)).thenReturn(plan)

        service.execute(rollbackCommand())

        val captor = argumentCaptor<WebhookOutboxEntry>()
        verify(webhookOutboxRepository).save(captor.capture())
        assertEquals("workflow.rolled_back", captor.firstValue.eventType)
        assertEquals(planId.value, captor.firstValue.transactionId.value)
    }

    @Test
    fun `plan status becomes ROLLED_BACK with rolledBackAt and rollbackReason`() {
        val plan =
            WorkflowPlan
                .create(planId, tenantId, agentContext, emptyList(), now)
                .withStatus(WorkflowStatus.COMMITTED)

        whenever(workflowPlanRepository.findById(planId, tenantId)).thenReturn(plan)

        service.execute(rollbackCommand())

        val statusCaptor = argumentCaptor<WorkflowStatus>()
        verify(workflowPlanRepository, times(1)).updateStatus(any(), any(), statusCaptor.capture(), anyOrNull(), anyOrNull(), anyOrNull())
        assertEquals(WorkflowStatus.ROLLED_BACK, statusCaptor.firstValue)
    }

    @Test
    fun `compensating transaction failure propagates as RuntimeException`() {
        val txId = TransactionId.generate()
        val plan =
            WorkflowPlan
                .create(planId, tenantId, agentContext, listOf("step-0"), now)
                .withStepExecuted(0, txId)
                .withStatus(WorkflowStatus.COMMITTED)

        whenever(workflowPlanRepository.findById(planId, tenantId)).thenReturn(plan)
        whenever(transactionRepository.findById(txId, tenantId)).thenReturn(originalTx(txId))
        whenever(postTransactionUseCase.execute(any()))
            .thenReturn(Result.failure(RuntimeException("ledger rejected")))

        val ex =
            org.junit.jupiter.api.assertThrows<RuntimeException> {
                service.execute(rollbackCommand())
            }
        assertTrue(ex.message!!.contains("step 0"))
        verify(webhookOutboxRepository, times(0)).save(any())
    }
}
