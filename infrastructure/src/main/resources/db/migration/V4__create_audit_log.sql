-- Append-only audit log. Every mutation (human or agent) writes here BEFORE execution.
-- HMAC-signed with tenant key for tamper detection.
-- FORCE ROW LEVEL SECURITY ensures even the table owner cannot bypass RLS.
CREATE TABLE audit_log (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL,
    transaction_id UUID     NOT NULL,
    agent_id    TEXT,
    intent      TEXT,
    action      TEXT        NOT NULL,
    created_by  TEXT        NOT NULL,
    payload     JSONB       NOT NULL,
    hmac        TEXT        NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_audit_log_tenant      ON audit_log (tenant_id, occurred_at DESC);
CREATE INDEX idx_audit_log_transaction ON audit_log (transaction_id);

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log FORCE ROW LEVEL SECURITY;

-- Read access: only own tenant's rows
CREATE POLICY tenant_read ON audit_log
    FOR SELECT
    USING (tenant_id = current_setting('app.tenant_id', true)::UUID);

-- Write access: only INSERT, only own tenant — no UPDATE/DELETE policy = those are always denied
CREATE POLICY tenant_insert ON audit_log
    FOR INSERT
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::UUID);
