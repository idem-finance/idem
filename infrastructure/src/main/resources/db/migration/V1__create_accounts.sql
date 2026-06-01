-- Ledger accounts within a tenant.
-- normal_balance is computed from type in the application layer — not persisted.
CREATE TABLE accounts (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL,
    name        TEXT        NOT NULL,
    description TEXT,
    currency    TEXT        NOT NULL,
    type        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  TEXT        NOT NULL,
    updated_at  TIMESTAMPTZ,
    updated_by  TEXT
);

CREATE INDEX idx_accounts_tenant ON accounts (tenant_id);

ALTER TABLE accounts ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON accounts
    FOR ALL
    USING      (tenant_id = current_setting('app.tenant_id', true)::UUID)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::UUID);
