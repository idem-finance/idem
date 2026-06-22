-- Append-only workflow-level audit log. Distinct from per-transaction audit_log.
-- "Updating" means inserting a second row (COMPLETED/FAILED) for the same workflow_plan_id.
-- Identical security model: FORCE RLS, REVOKE UPDATE/DELETE.

CREATE TABLE agent_audit_events (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_plan_id UUID        NOT NULL,
    tenant_id        UUID        NOT NULL,
    agent_id         TEXT        NOT NULL,
    session_id       TEXT        NOT NULL,
    intent           TEXT,
    status           TEXT        NOT NULL,
    outcome          TEXT,
    payload          JSONB       NOT NULL,
    hmac             TEXT        NOT NULL,
    occurred_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_agent_audit_status
        CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_agent_audit_events_tenant        ON agent_audit_events (tenant_id, occurred_at DESC);
CREATE INDEX idx_agent_audit_events_workflow_plan ON agent_audit_events (workflow_plan_id);

ALTER TABLE agent_audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_audit_events FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_read ON agent_audit_events
    FOR SELECT
    USING (tenant_id = current_setting('app.tenant_id', true)::UUID);

CREATE POLICY tenant_insert ON agent_audit_events
    FOR INSERT
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::UUID);

REVOKE UPDATE, DELETE ON agent_audit_events FROM PUBLIC;
