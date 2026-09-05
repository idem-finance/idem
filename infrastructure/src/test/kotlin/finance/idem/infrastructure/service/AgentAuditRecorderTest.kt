package finance.idem.infrastructure.service

import finance.idem.application.events.DomainEvent
import finance.idem.application.port.AgentAuditRepository
import finance.idem.application.port.DomainEventRepository
import finance.idem.core.TenantId
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.AgentAuditEvent
import finance.idem.core.agentic.AgentContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class AgentAuditRecorderTest {
    @Mock lateinit var agentAuditRepository: AgentAuditRepository

    @Mock lateinit var domainEventRepository: DomainEventRepository

    private val workflowPlanId = WorkflowPlanId.generate()
    private val tenantId = TenantId.generate()

    private fun event(): AgentAuditEvent =
        AgentAuditEvent.failed(
            workflowPlanId = workflowPlanId,
            tenantId = tenantId,
            agentContext = AgentContext(agentId = "agent-1", sessionId = "sess-1"),
            outcome = "Policy denied: over limit",
        )

    private fun domainEvent(): DomainEvent =
        DomainEvent.agentActionFlagged(
            workflowPlanId = workflowPlanId,
            tenantId = tenantId,
            correlationId = "trace-1",
        )

    @Test
    fun `recordDurable delegates to the audit repository`() {
        val recorder = AgentAuditRecorder(agentAuditRepository, domainEventRepository)
        val event = event()

        recorder.recordDurable(event)

        verify(agentAuditRepository).save(event)
        verifyNoInteractions(domainEventRepository)
    }

    @Test
    fun `a repository failure propagates to the caller so callers can log it`() {
        val recorder = AgentAuditRecorder(agentAuditRepository, domainEventRepository)
        whenever(agentAuditRepository.save(any()))
            .thenThrow(RuntimeException("db down"))

        // The recorder must NOT swallow the exception itself — the calling service wraps the
        // invocation in runCatching. Swallowing inside the REQUIRES_NEW method would risk an
        // UnexpectedRollbackException at the transaction boundary.
        assertThrows<RuntimeException> { recorder.recordDurable(event()) }
    }

    @Test
    fun `recordDurable(event, domainEvent) delegates to both repositories`() {
        val recorder = AgentAuditRecorder(agentAuditRepository, domainEventRepository)
        val event = event()
        val domainEvent = domainEvent()

        recorder.recordDurable(event, domainEvent)

        verify(agentAuditRepository).save(event)
        verify(domainEventRepository).save(domainEvent)
    }

    @Test
    fun `a domain event repository failure propagates to the caller`() {
        val recorder = AgentAuditRecorder(agentAuditRepository, domainEventRepository)
        whenever(domainEventRepository.save(any()))
            .thenThrow(RuntimeException("db down"))

        assertThrows<RuntimeException> { recorder.recordDurable(event(), domainEvent()) }
    }
}
