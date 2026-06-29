package finance.idem.infrastructure.service

import finance.idem.application.agentic.ExecuteWorkflowCommand
import finance.idem.application.agentic.WorkflowStepCommand
import finance.idem.application.ledger.JournalLineRequest
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
import finance.idem.core.agentic.AgentAuditEvent
import finance.idem.core.agentic.AgentAuditStatus
import finance.idem.application.agentic.SessionDebitPort
import finance.idem.core.agentic.AgentContext
import finance.idem.core.agentic.PolicyRepository
import finance.idem.core.agentic.PolicyRule
import finance.idem.core.agentic.PolicyViolationException
import finance.idem.core.agentic.StepStatus
import finance.idem.core.agentic.WorkflowPlanRepository
import finance.idem.core.agentic.WorkflowStatus
import finance.idem.core.agentic.WorkflowStep
import finance.idem.core.monetary.FiatEntry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExecuteWorkflowServiceTest {

    @Mock lateinit var workflowPlanRepository: WorkflowPlanRepository
    @Mock lateinit var agentAuditRepository: AgentAuditRepository
    @Mock lateinit var webhookOutboxRepository: WebhookOutboxRepository
    @Mock lateinit var postTransactionUseCase: PostTransactionUseCase
    @Mock lateinit var policyRepository: PolicyRepository
    @Mock lateinit var sessionDebitPort: SessionDebitPort

    private lateinit var service: ExecuteWorkflowService

    private val tenantId = TenantId.generate()
    private val debitAccountId = AccountId.generate()
    private val creditAccountId = AccountId.generate()
    private val agentContext = AgentContext(agentId = "agent-1", sessionId = "sess-1", intent = "offramp")

    @BeforeEach
    fun setUp() {
        service = ExecuteWorkflowService(
            workflowPlanRepository,
            agentAuditRepository,
            webhookOutboxRepository,
            postTransactionUseCase,
            policyRepository,
            sessionDebitPort,
        )
        whenever(policyRepository.findEffective(any(), anyOrNull()))
            .thenReturn(listOf(PolicyRule.MaxDebitPerSession(MonetaryAmount.of("99999"))))
        whenever(sessionDebitPort.sumDebitsForSession(any(), any())).thenReturn(MonetaryAmount.ZERO)
        whenever(sessionDebitPort.sumDebitsLastHour(any())).thenReturn(MonetaryAmount.ZERO)
    }

    private fun brlLine(accountId: AccountId, type: EntryType) = JournalLineRequest(
        accountId = accountId,
        entryType = type,
        monetaryEntry = FiatEntry(MonetaryAmount.of("100"), FiatCurrency.BRL, PaymentRail.PIX),
    )

    private fun twoStepCommand() = ExecuteWorkflowCommand(
        tenantId = tenantId,
        agentContext = agentContext,
        steps = listOf(
            WorkflowStepCommand("step-0-idem", lines = listOf(brlLine(debitAccountId, EntryType.DEBIT), brlLine(creditAccountId, EntryType.CREDIT))),
            WorkflowStepCommand("step-1-idem", lines = listOf(brlLine(debitAccountId, EntryType.DEBIT), brlLine(creditAccountId, EntryType.CREDIT))),
        ),
        createdBy = "sk_agent_test",
    )

    @Test
    fun `happy path — two steps succeed and plan reaches COMMITTED`() {
        val txId0 = TransactionId.generate()
        val txId1 = TransactionId.generate()
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.success(txId0), Result.success(txId1))

        val result = service.execute(twoStepCommand())

        assertTrue(result.isSuccess)

        verify(workflowPlanRepository, times(1)).insert(any())

        val statusCaptor = argumentCaptor<WorkflowStatus>()
        verify(workflowPlanRepository, times(2)).updateStatus(any(), any(), statusCaptor.capture(), anyOrNull(), anyOrNull(), anyOrNull())
        assertEquals(WorkflowStatus.EXECUTING, statusCaptor.allValues[0])
        assertEquals(WorkflowStatus.COMMITTED, statusCaptor.allValues[1])

        verify(workflowPlanRepository, times(2)).updateStep(any(), any(), any())

        val auditCaptor = argumentCaptor<AgentAuditEvent>()
        verify(agentAuditRepository, times(2)).save(auditCaptor.capture())
        assertEquals(AgentAuditStatus.PENDING, auditCaptor.allValues[0].status)
        assertEquals(AgentAuditStatus.COMPLETED, auditCaptor.allValues[1].status)

        val outboxCaptor = argumentCaptor<WebhookOutboxEntry>()
        verify(webhookOutboxRepository).save(outboxCaptor.capture())
        assertEquals("workflow.committed", outboxCaptor.firstValue.eventType)
    }

    @Test
    fun `PolicyViolationException thrown when rules are Denied — no plan created`() {
        whenever(policyRepository.findEffective(any(), anyOrNull()))
            .thenReturn(listOf(PolicyRule.MaxDebitPerSession(MonetaryAmount.ZERO)))

        assertThrows<PolicyViolationException> {
            service.execute(twoStepCommand())
        }

        verify(workflowPlanRepository, times(0)).insert(any())
        verify(workflowPlanRepository, times(0)).updateStatus(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull())
        verify(agentAuditRepository, times(0)).save(any())
    }

    @Test
    fun `step failure — plan becomes FAILED and FAILED audit event written`() {
        val txId0 = TransactionId.generate()
        whenever(postTransactionUseCase.execute(any()))
            .thenReturn(Result.success(txId0))
            .thenReturn(Result.failure(RuntimeException("account not found")))

        assertThrows<RuntimeException> {
            service.execute(twoStepCommand())
        }

        verify(workflowPlanRepository, times(1)).insert(any())

        val statusCaptor = argumentCaptor<WorkflowStatus>()
        verify(workflowPlanRepository, times(2)).updateStatus(any(), any(), statusCaptor.capture(), anyOrNull(), anyOrNull(), anyOrNull())
        assertEquals(WorkflowStatus.EXECUTING, statusCaptor.allValues[0])
        assertEquals(WorkflowStatus.FAILED, statusCaptor.allValues[1])

        val stepCaptor = argumentCaptor<WorkflowStep>()
        verify(workflowPlanRepository, times(2)).updateStep(any(), any(), stepCaptor.capture())
        assertNotNull(stepCaptor.allValues[0].transactionId)
        assertEquals(StepStatus.FAILED, stepCaptor.allValues[1].status)

        val auditCaptor = argumentCaptor<AgentAuditEvent>()
        verify(agentAuditRepository, times(2)).save(auditCaptor.capture())
        assertEquals(AgentAuditStatus.PENDING, auditCaptor.allValues[0].status)
        assertEquals(AgentAuditStatus.FAILED, auditCaptor.allValues[1].status)

        verify(webhookOutboxRepository, times(0)).save(any())
    }

    @Test
    fun `operation ordering — pending audit before steps, completed audit and outbox after`() {
        val txId = TransactionId.generate()
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.success(txId), Result.success(txId))

        service.execute(twoStepCommand())

        val order = inOrder(agentAuditRepository, postTransactionUseCase, webhookOutboxRepository)
        order.verify(agentAuditRepository).save(any())
        order.verify(postTransactionUseCase, times(2)).execute(any())
        order.verify(agentAuditRepository).save(any())
        order.verify(webhookOutboxRepository).save(any())
    }

    @Test
    fun `agentContext in step commands carries the generated workflowPlanId`() {
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.success(TransactionId.generate()))

        service.execute(ExecuteWorkflowCommand(
            tenantId = tenantId,
            agentContext = agentContext,
            steps = listOf(
                WorkflowStepCommand("single-step", lines = listOf(brlLine(debitAccountId, EntryType.DEBIT), brlLine(creditAccountId, EntryType.CREDIT))),
            ),
            createdBy = "sk_agent_test",
        ))

        val captor = argumentCaptor<finance.idem.application.ledger.PostTransactionCommand>()
        verify(postTransactionUseCase).execute(captor.capture())
        val stepContext = captor.firstValue.agentContext
        assertTrue(stepContext?.workflowPlanId != null)
    }
}
