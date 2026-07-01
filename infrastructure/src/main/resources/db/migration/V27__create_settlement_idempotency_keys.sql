-- Idempotency key store for settlement registration. Mirrors idempotency_keys (V7) —
-- tryRecord() uses INSERT ON CONFLICT DO NOTHING to atomically claim a key before
-- any settlement writes, preventing duplicate PENDING settlements from a retried
-- POST /api/v1/settlements. Separate table (rather than reusing
-- idempotency_keys) because that table's transaction_id column is transaction-specific.
CREATE TABLE settlement_idempotency_keys (
    tenant_id      UUID        NOT NULL,
    key            TEXT        NOT NULL,
    settlement_id  UUID        NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (tenant_id, key)
);

CREATE INDEX idx_settlement_idempotency_keys_expiry ON settlement_idempotency_keys (expires_at);

ALTER TABLE settlement_idempotency_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE settlement_idempotency_keys FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON settlement_idempotency_keys
    FOR ALL
    USING      (tenant_id = current_setting('app.tenant_id', true)::UUID)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::UUID);
