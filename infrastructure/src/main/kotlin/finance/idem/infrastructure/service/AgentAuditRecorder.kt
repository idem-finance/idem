package finance.idem.infrastructure.service

import finance.idem.application.events.DomainEvent
import finance.idem.application.port.AgentAuditRepository
import finance.idem.application.port.DomainEventRepository
import finance.idem.core.agentic.AgentAuditEvent
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Persists an [AgentAuditEvent] in its own committed transaction, independent of the
 * caller's business transaction.
 *
 * Rationale: agent audit events for *denied* and *failed* workflows must survive even
 * though the business operation rolls back. Writing them with the caller's default
 * `REQUIRED` propagation would let the rollback discard them too (the pre-fix behaviour).
 * `REQUIRES_NEW` suspends the outer transaction and commits this insert on its own — the
 * append-only, tamper-evident audit trail is preserved regardless of the outcome.
 *
 * Callers MUST wrap invocations in `runCatching { recordDurable(event) }` and log failures:
 * a swallow-and-log guard cannot live inside this `@Transactional` method (that would leave
 * the suspended transaction rollback-only and surface `UnexpectedRollbackException` at the
 * boundary). Catching the exception in the caller is safe — it does not mark the outer
 * business transaction rollback-only.
 *
 * `AgentAuditRepository.save` sets the RLS tenant context (`SET LOCAL app.tenant_id`) at the
 * top of its own transactional method; because this runs on a fresh connection the tenant
 * scope is established correctly for the independent insert.
 */
@Component
class AgentAuditRecorder(
    private val agentAuditRepository: AgentAuditRepository,
    private val domainEventRepository: DomainEventRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordDurable(event: AgentAuditEvent) {
        agentAuditRepository.save(event)
    }

    /**
     * Same `REQUIRES_NEW` transaction as [recordDurable], but also durably records a
     * `domain_events` row (e.g. `AGENT_ACTION_FLAGGED` for a PolicyGuard denial) — both writes
     * commit or roll back together, independent of the caller's business transaction, for the
     * same reason [recordDurable] itself is `REQUIRES_NEW` (see class KDoc).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordDurable(
        event: AgentAuditEvent,
        domainEvent: DomainEvent,
    ) {
        agentAuditRepository.save(event)
        domainEventRepository.save(domainEvent)
    }
}
