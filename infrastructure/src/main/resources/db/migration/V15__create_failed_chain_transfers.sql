-- Dead-letter record for on-chain transfers whose PostTransactionUseCase.execute() returned
-- Result.failure during chain polling/webhook processing. The chain checkpoint still advances
-- past these (see ChainReaderOrchestrator), so this table is the only durable record an
-- operator can use to detect and manually correct a dropped on-chain entry.
-- No RLS: written by cross-tenant background processes (chain readers, webhook receivers)
-- that never set app.tenant_id — same rationale as chain_checkpoint (V6).
CREATE TABLE failed_chain_transfers (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_key         TEXT        NOT NULL,
    source            TEXT        NOT NULL,
    idempotency_key   TEXT        NOT NULL,
    tx_hash           TEXT        NOT NULL,
    block_number      BIGINT      NOT NULL,
    tenant_id         UUID        NOT NULL,
    wallet_address    TEXT        NOT NULL,
    token_contract    TEXT        NOT NULL,
    debit_account_id  UUID        NOT NULL,
    credit_account_id UUID        NOT NULL,
    token             TEXT        NOT NULL,
    amount            NUMERIC     NOT NULL,
    error_message     TEXT        NOT NULL,
    resolved          BOOLEAN     NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at       TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_failed_chain_transfers_idempotency_key
    ON failed_chain_transfers (idempotency_key);

CREATE INDEX idx_failed_chain_transfers_unresolved
    ON failed_chain_transfers (resolved, created_at)
    WHERE resolved = false;
