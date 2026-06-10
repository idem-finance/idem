-- Settlements: customer-registered expectations of incoming on-chain
-- transfers (status=PENDING), plus orphan on-chain receipts with no matching
-- expectation (status=UNMATCHED), auto-created by BasicReconciliationService.
-- See docs/reconciliation.md.
CREATE TABLE settlements (
    id                     UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID           NOT NULL,
    account_id             UUID           NOT NULL,
    amount                 NUMERIC(38,18) NOT NULL,
    token                  TEXT           NOT NULL,
    chain_id               TEXT           NOT NULL,
    wallet_address         TEXT           NOT NULL,
    status                 TEXT           NOT NULL,
    matched_transaction_id UUID,
    tx_hash                TEXT,
    block_number           BIGINT,
    confirmed_at           TIMESTAMPTZ,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by             TEXT           NOT NULL,

    CONSTRAINT fk_settlements_account
        FOREIGN KEY (account_id, tenant_id) REFERENCES accounts (id, tenant_id),
    CONSTRAINT fk_settlements_transaction
        FOREIGN KEY (matched_transaction_id, tenant_id) REFERENCES transactions (id, tenant_id)
);

CREATE INDEX idx_settlements_matching
    ON settlements (tenant_id, status, account_id, token, chain_id, wallet_address, created_at);

ALTER TABLE settlements ENABLE ROW LEVEL SECURITY;
ALTER TABLE settlements FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON settlements
    FOR ALL
    USING      (tenant_id = current_setting('app.tenant_id', true)::UUID)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::UUID);
