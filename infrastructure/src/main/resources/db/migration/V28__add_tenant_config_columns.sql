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
