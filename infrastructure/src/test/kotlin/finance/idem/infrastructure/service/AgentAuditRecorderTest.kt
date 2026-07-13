package finance.idem.infrastructure.service

import finance.idem.application.port.AgentAuditRepository
import finance.idem.core.TenantId
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.AgentAuditEvent
import finance.idem.core.agentic.AgentContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class AgentAuditRecorderTest {
    @Mock lateinit var agentAuditRepository: AgentAuditRepository

    private fun event(): AgentAuditEvent =
        AgentAuditEvent.failed(
            workflowPlanId = WorkflowPlanId.generate(),
            tenantId = TenantId.generate(),
            agentContext = AgentContext(agentId = "agent-1", sessionId = "sess-1"),
            outcome = "Policy denied: over limit",
        )

    @Test
    fun `recordDurable delegates to the audit repository`() {
        val recorder = AgentAuditRecorder(agentAuditRepository)
        val event = event()

        recorder.recordDurable(event)

        verify(agentAuditRepository).save(event)
    }

    @Test
    fun `a repository failure propagates to the caller so callers can log it`() {
        val recorder = AgentAuditRecorder(agentAuditRepository)
        whenever(agentAuditRepository.save(org.mockito.kotlin.any()))
            .thenThrow(RuntimeException("db down"))

        // The recorder must NOT swallow the exception itself — the calling service wraps the
        // invocation in runCatching. Swallowing inside the REQUIRES_NEW method would risk an
        // UnexpectedRollbackException at the transaction boundary.
        assertThrows<RuntimeException> { recorder.recordDurable(event()) }
    }
}
