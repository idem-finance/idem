package finance.idem.infrastructure.service

import finance.idem.application.agentic.ExecuteWorkflowCommand
import finance.idem.application.agentic.WorkflowStepCommand
import finance.idem.application.ledger.JournalLineRequest
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
import finance.idem.core.agentic.AgentAuditEvent
import finance.idem.core.agentic.AgentAuditStatus
import finance.idem.core.agentic.AgentContext
import finance.idem.core.agentic.PolicyRule
import finance.idem.core.agentic.PolicyViolationException
import finance.idem.core.agentic.WorkflowPlan
import finance.idem.core.agentic.WorkflowPlanStatus
import finance.idem.core.monetary.FiatEntry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExtendWith(MockitoExtension::class)
class ExecuteWorkflowServiceTest {

    @Mock lateinit var workflowPlanRepository: WorkflowPlanRepository
    @Mock lateinit var agentAuditRepository: AgentAuditRepository
    @Mock lateinit var webhookOutboxRepository: WebhookOutboxRepository
    @Mock lateinit var postTransactionUseCase: PostTransactionUseCase

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
        )
    }

    private fun brlLine(accountId: AccountId, type: EntryType) = JournalLineRequest(
        accountId = accountId,
        entryType = type,
        monetaryEntry = FiatEntry(MonetaryAmount.of("100"), FiatCurrency.BRL, PaymentRail.PIX),
    )

    private fun twoStepCommand(policyRules: List<PolicyRule> = emptyList()) = ExecuteWorkflowCommand(
        tenantId = tenantId,
        agentContext = agentContext,
        steps = listOf(
            WorkflowStepCommand("step-0-idem", listOf(brlLine(debitAccountId, EntryType.DEBIT), brlLine(creditAccountId, EntryType.CREDIT))),
            WorkflowStepCommand("step-1-idem", listOf(brlLine(debitAccountId, EntryType.DEBIT), brlLine(creditAccountId, EntryType.CREDIT))),
        ),
        policyRules = policyRules,
        createdBy = "sk_agent_test",
    )

    @Test
    fun `happy path — two steps succeed and plan reaches COMMITTED`() {
        val txId0 = TransactionId.generate()
        val txId1 = TransactionId.generate()
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.success(txId0), Result.success(txId1))

        val result = service.execute(twoStepCommand())

        assertTrue(result.isSuccess)

        val planCaptor = argumentCaptor<WorkflowPlan>()
        verify(workflowPlanRepository, times(5)).save(planCaptor.capture())
        val statuses = planCaptor.allValues.map { it.status }
        assertEquals(WorkflowPlanStatus.PLANNED, statuses[0])
        assertEquals(WorkflowPlanStatus.EXECUTING, statuses[1])
        // steps 2 and 3 are after each step execution; status stays EXECUTING
        assertEquals(WorkflowPlanStatus.COMMITTED, statuses[4])

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
        // MaxDebitPerSession(0): any positive debit violates
        val rules = listOf(PolicyRule.MaxDebitPerSession(MonetaryAmount.ZERO))

        assertThrows<PolicyViolationException> {
            service.execute(twoStepCommand(policyRules = rules))
        }

        verify(workflowPlanRepository, times(0)).save(any())
        verify(agentAuditRepository, times(0)).save(any())
    }

    @Test
    fun `step failure rolls back — plan becomes ROLLED_BACK and FAILED audit event written`() {
        val txId0 = TransactionId.generate()
        whenever(postTransactionUseCase.execute(any()))
            .thenReturn(Result.success(txId0))
            .thenReturn(Result.failure(RuntimeException("account not found")))

        assertThrows<RuntimeException> {
            service.execute(twoStepCommand())
        }

        val planCaptor = argumentCaptor<WorkflowPlan>()
        verify(workflowPlanRepository, times(4)).save(planCaptor.capture())
        val lastStatus = planCaptor.allValues.last().status
        assertEquals(WorkflowPlanStatus.ROLLED_BACK, lastStatus)

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
        order.verify(agentAuditRepository).save(any())                // PENDING audit before steps
        order.verify(postTransactionUseCase, times(2)).execute(any()) // all steps execute
        order.verify(agentAuditRepository).save(any())                // COMPLETED audit after steps
        order.verify(webhookOutboxRepository).save(any())             // outbox is last
    }

    @Test
    fun `agentContext in step commands carries the generated workflowPlanId`() {
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.success(TransactionId.generate()))

        service.execute(ExecuteWorkflowCommand(
            tenantId = tenantId,
            agentContext = agentContext,
            steps = listOf(
                WorkflowStepCommand("single-step", listOf(brlLine(debitAccountId, EntryType.DEBIT), brlLine(creditAccountId, EntryType.CREDIT))),
            ),
            createdBy = "sk_agent_test",
        ))

        val captor = argumentCaptor<finance.idem.application.ledger.PostTransactionCommand>()
        verify(postTransactionUseCase).execute(captor.capture())
        val stepContext = captor.firstValue.agentContext
        assertTrue(stepContext?.workflowPlanId != null)
    }
}
