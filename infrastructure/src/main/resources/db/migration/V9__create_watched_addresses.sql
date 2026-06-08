CREATE TABLE watched_addresses (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    chain_key         TEXT        NOT NULL,
    wallet_address    TEXT        NOT NULL,
    token_contract    TEXT        NOT NULL,
    token             TEXT        NOT NULL,
    tenant_id         UUID        NOT NULL,
    debit_account_id  UUID        NOT NULL,
    credit_account_id UUID        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_watched_addresses UNIQUE (chain_key, wallet_address, token_contract, tenant_id),
    CONSTRAINT fk_watched_addresses_debit_account
        FOREIGN KEY (debit_account_id,  tenant_id) REFERENCES accounts (id, tenant_id),
    CONSTRAINT fk_watched_addresses_credit_account
        FOREIGN KEY (credit_account_id, tenant_id) REFERENCES accounts (id, tenant_id)
);

CREATE INDEX idx_watched_addresses_chain_key ON watched_addresses (chain_key);
