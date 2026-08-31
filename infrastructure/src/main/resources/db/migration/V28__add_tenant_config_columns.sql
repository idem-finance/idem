-- Extends `tenants` (V13) with plan/limits/flags/security config (#274), as its own
-- migration comment anticipated: "Grows over time (name, plan, etc.)". Inherits V13's
-- existing tenant_isolation policy and NO FORCE ROW LEVEL SECURITY.
ALTER TABLE tenants
    ADD COLUMN plan TEXT NOT NULL DEFAULT 'OPEN_SOURCE'
        CHECK (plan IN ('OPEN_SOURCE', 'CLOUD', 'ENTERPRISE')),
    ADD COLUMN rate_limit_per_second INT,
    ADD COLUMN rate_limit_per_minute INT,
    -- Comma-delimited feature flag names, mirrors api_keys.scopes (V8).
    ADD COLUMN feature_flags TEXT NOT NULL DEFAULT '',
    -- Per-tenant HMAC key for AgentAuditEvent signing. NULL falls back to the global
    -- IDEM_AUDIT_HMAC_SECRET (AuditProperties) so existing installs upgrade with no
    -- audit-verification breakage.
    ADD COLUMN hmac_key TEXT,
    ADD COLUMN billing_customer_id TEXT,
    ADD COLUMN suspended_at TIMESTAMPTZ;

-- hmac_key inherits this table's NO FORCE ROW LEVEL SECURITY (the table-owner role
-- bypasses RLS for cross-tenant reads such as WebhookOutboxPoller resolving
-- webhook_url/webhook_secret) -- an accepted, defense-in-depth-only tradeoff at the
-- same exposure level webhook_secret has carried since V13. Documented here (not
-- fixed): a table split would be needed to isolate hmac_key from this exemption,
-- and isn't warranted without a driving incident.
COMMENT ON COLUMN tenants.hmac_key IS
    'Per-tenant HMAC key for AgentAuditEvent signing. Inherits this table''s '
    'NO FORCE ROW LEVEL SECURITY exemption (see V13) -- same accepted, '
    'defense-in-depth-only tradeoff already applied to webhook_secret.';
