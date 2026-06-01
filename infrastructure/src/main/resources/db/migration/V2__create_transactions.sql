-- Transaction aggregate root. Owns the double-entry invariant.
-- occurred_at is the business event time; created_at is when the record was persisted.
CREATE TABLE transactions (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID        NOT NULL,
    idempotency_key  TEXT        NOT NULL,
    status           TEXT        NOT NULL,
    agent_context    JSONB,
    metadata         JSONB       NOT NULL DEFAULT '{}',
    occurred_at      TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       TEXT        NOT NULL,

    CONSTRAINT uq_transactions_tenant_idempotency UNIQUE (tenant_id, idempotency_key)
);

CREATE INDEX idx_transactions_tenant ON transactions (tenant_id);
CREATE INDEX idx_transactions_occurred_at ON transactions (tenant_id, occurred_at DESC);

ALTER TABLE transactions ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON transactions
    FOR ALL
    USING      (tenant_id = current_setting('app.tenant_id', true)::UUID)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::UUID);
