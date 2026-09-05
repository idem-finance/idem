package finance.idem.core.events

/**
 * One value per domain mutation worth recording in the append-only `domain_events` log.
 * Values correspond 1:1 with the event-type strings already used by
 * `finance.idem.application.outbox.WebhookOutboxEntry`, translated to SCREAMING_SNAKE, plus
 * [AGENT_ACTION_FLAGGED] — new, for PolicyGuard denials, which otherwise have no
 * representation beyond a generic `AgentAuditStatus.FAILED` + free-text outcome.
 */
enum class DomainEventType {
    TRANSACTION_COMMITTED,
    TRANSACTION_SETTLED,
    RECONCILIATION_UNMATCHED,
    RECONCILIATION_EXCEPTION,
    WORKFLOW_COMMITTED,
    WORKFLOW_ROLLED_BACK,
    COMPLIANCE_TRAVEL_RULE_REQUIRED,
    AGENT_ACTION_FLAGGED,
}

/** What [finance.idem.application.events.DomainEvent.referenceId] points at. */
enum class DomainEventReferenceType {
    TRANSACTION,
    WORKFLOW,
}
