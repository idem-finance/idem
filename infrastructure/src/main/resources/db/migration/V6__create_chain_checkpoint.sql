-- Last scanned block per chain. Prevents re-scanning on ChainReaderService restart.
-- No RLS: global state, not tenant-scoped.
CREATE TABLE chain_checkpoint (
    chain_id   TEXT        PRIMARY KEY,
    last_block BIGINT      NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
