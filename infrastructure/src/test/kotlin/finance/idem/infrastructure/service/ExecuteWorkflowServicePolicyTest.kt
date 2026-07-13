package finance.idem.infrastructure.service

import finance.idem.application.agentic.ExecuteWorkflowCommand
import finance.idem.application.agentic.SessionDebitPort
import finance.idem.application.agentic.WorkflowStepCommand
import finance.idem.application.ledger.JournalLineRequest
import finance.idem.application.ledger.PostTransactionUseCase
import finance.idem.application.port.AgentAuditRepository
import finance.idem.application.port.WebhookOutboxRepository
import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.agentic.AgentContext
import finance.idem.core.agentic.PolicyRepository
import finance.idem.core.agentic.PolicyRule
import finance.idem.core.agentic.PolicyViolationException
import finance.idem.core.agentic.WorkflowPlanRepository
import finance.idem.core.monetary.FiatEntry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExecuteWorkflowServicePolicyTest {
    @Mock lateinit var workflowPlanRepository: WorkflowPlanRepository

    @Mock lateinit var agentAuditRepository: AgentAuditRepository

    @Mock lateinit var agentAuditRecorder: AgentAuditRecorder

    @Mock lateinit var webhookOutboxRepository: WebhookOutboxRepository

    @Mock lateinit var postTransactionUseCase: PostTransactionUseCase

    @Mock lateinit var policyRepository: PolicyRepository

    @Mock lateinit var sessionDebitPort: SessionDebitPort

    private lateinit var service: ExecuteWorkflowService

    private val tenantId = TenantId.generate()
    private val debitId = AccountId.generate()
    private val creditId = AccountId.generate()
    private val agentContext = AgentContext(agentId = "agent-1", sessionId = "sess-1", apiKeyPrefix = "sk_agent_abc1")

    @BeforeEach
    fun setUp() {
        service =
            ExecuteWorkflowService(
                workflowPlanRepository,
                agentAuditRepository,
                agentAuditRecorder,
                webhookOutboxRepository,
                postTransactionUseCase,
                policyRepository,
                sessionDebitPort,
            )
        whenever(sessionDebitPort.sumDebitsForSession(any(), any())).thenReturn(MonetaryAmount.ZERO)
        whenever(sessionDebitPort.sumDebitsLastHour(any(), anyOrNull())).thenReturn(MonetaryAmount.ZERO)
    }

    private fun cmd() =
        ExecuteWorkflowCommand(
            tenantId = tenantId,
            agentContext = agentContext,
            steps =
                listOf(
                    WorkflowStepCommand(
                        "key-1",
                        lines =
                            listOf(
                                JournalLineRequest(
                                    debitId,
                                    EntryType.DEBIT,
                                    FiatEntry(MonetaryAmount.of("100"), FiatCurrency.BRL, PaymentRail.PIX),
                                ),
                                JournalLineRequest(
                                    creditId,
                                    EntryType.CREDIT,
                                    FiatEntry(MonetaryAmount.of("100"), FiatCurrency.BRL, PaymentRail.PIX),
                                ),
                            ),
                    ),
                ),
            createdBy = "agent-1",
        )

    @Test
    fun `no configured rules — default MaxDebitPerSession(ZERO) blocks all debits`() {
        whenever(policyRepository.findEffective(any(), anyOrNull())).thenReturn(emptyList())

        assertThrows<PolicyViolationException> { service.execute(cmd()) }

        verify(workflowPlanRepository, times(0)).insert(any())
    }

    @Test
    fun `permissive rule configured — transaction proceeds`() {
        val permissiveRule = PolicyRule.MaxDebitPerSession(MonetaryAmount.of("99999"))
        whenever(policyRepository.findEffective(any(), anyOrNull())).thenReturn(listOf(permissiveRule))
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.success(TransactionId.generate()))

        val result = service.execute(cmd())

        assertTrue(result.isSuccess)
        verify(workflowPlanRepository, times(1)).insert(any())
    }

    @Test
    fun `deny-all rule configured — PolicyViolationException thrown before plan created`() {
        val denyRule = PolicyRule.MaxDebitPerSession(MonetaryAmount.ZERO)
        whenever(policyRepository.findEffective(any(), anyOrNull())).thenReturn(listOf(denyRule))

        assertThrows<PolicyViolationException> { service.execute(cmd()) }

        verify(workflowPlanRepository, times(0)).insert(any())
        verify(agentAuditRepository, times(0)).save(any())
    }

    @Test
    fun `prior session debit total is accumulated for MaxDebitPerSession evaluation`() {
        val limit = MonetaryAmount.of("150")
        whenever(policyRepository.findEffective(any(), anyOrNull()))
            .thenReturn(listOf(PolicyRule.MaxDebitPerSession(limit)))
        // Prior session total is 100; this workflow adds another 100 → 200 > 150 → denied
        whenever(sessionDebitPort.sumDebitsForSession(any(), any())).thenReturn(MonetaryAmount.of("100"))

        assertThrows<PolicyViolationException> { service.execute(cmd()) }

        verify(workflowPlanRepository, times(0)).insert(any())
    }

    @Test
    fun `policy repository called with tenant id and api key prefix from agent context`() {
        whenever(policyRepository.findEffective(any(), anyOrNull()))
            .thenReturn(listOf(PolicyRule.MaxDebitPerSession(MonetaryAmount.of("99999"))))
        whenever(postTransactionUseCase.execute(any())).thenReturn(Result.success(TransactionId.generate()))

        service.execute(cmd())

        verify(policyRepository).findEffective(tenantId, agentContext.apiKeyPrefix)
    }
}

private fun assertTrue(value: Boolean) = kotlin.test.assertTrue(value)
