-- One side of a double-entry posting. Immutable once created.
-- Hybrid storage: amount + currency columns for efficient balance queries;
-- monetary_entry_data JSONB preserves the full sealed-class payload for reconstruction.
CREATE TABLE journal_lines (
    id                   UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id       UUID           NOT NULL REFERENCES transactions (id),
    account_id           UUID           NOT NULL REFERENCES accounts (id),
    tenant_id            UUID           NOT NULL,
    entry_type           TEXT           NOT NULL,
    amount               NUMERIC(38,18) NOT NULL,
    currency             TEXT           NOT NULL,
    monetary_entry_type  TEXT           NOT NULL,
    monetary_entry_data  JSONB          NOT NULL,
    description          TEXT,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by           TEXT           NOT NULL
);

CREATE INDEX idx_journal_lines_transaction ON journal_lines (transaction_id);
CREATE INDEX idx_journal_lines_account     ON journal_lines (account_id, tenant_id);

ALTER TABLE journal_lines ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON journal_lines
    FOR ALL
    USING      (tenant_id = current_setting('app.tenant_id', true)::UUID)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::UUID);
