-- Per-tenant agent policy rules. Evaluated by PolicyGuard before every agent-originated
-- transaction. Rows with agent_key_prefix IS NULL apply to all agents of the tenant;
-- rows with a non-null prefix apply only to that specific agent key.
-- When no enabled rows exist for a tenant the service applies a deny-all default.
CREATE TABLE policy_rules (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID        NOT NULL,
    agent_key_prefix  VARCHAR(12),
    rule_type         VARCHAR(60) NOT NULL,
    params            JSONB       NOT NULL DEFAULT '{}',
    enabled           BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_policy_rules_tenant ON policy_rules (tenant_id);
CREATE INDEX idx_policy_rules_lookup ON policy_rules (tenant_id, agent_key_prefix, enabled);

ALTER TABLE policy_rules ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON policy_rules
    FOR ALL
    USING      (tenant_id = current_setting('app.tenant_id', true)::UUID)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::UUID);

-- FORCE: policy_rules is always accessed in authenticated tenant context (admin API + MCP service).
-- Matches workflow_plans / agent_audit_events pattern.
ALTER TABLE policy_rules FORCE ROW LEVEL SECURITY;
