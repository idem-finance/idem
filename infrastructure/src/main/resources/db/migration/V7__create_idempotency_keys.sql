-- Idempotency key store. tryRecord() uses INSERT ON CONFLICT DO NOTHING to atomically
-- claim a key before any ledger writes, preventing concurrent duplicate transactions.
-- Composite PK (tenant_id, key) eliminates the surrogate id column and makes the
-- uniqueness invariant structural rather than a secondary constraint.
CREATE TABLE idempotency_keys (
    tenant_id       UUID        NOT NULL,
    key             TEXT        NOT NULL,
    transaction_id  UUID        NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (tenant_id, key)
);

-- TTL cleanup job scans by expires_at
CREATE INDEX idx_idempotency_keys_expiry ON idempotency_keys (expires_at);

ALTER TABLE idempotency_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_keys FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON idempotency_keys
    FOR ALL
    USING      (tenant_id = current_setting('app.tenant_id', true)::UUID)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::UUID);
