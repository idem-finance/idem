-- API key store. Rows are never deleted; revocation sets revoked_at.
-- key_hash is BCrypt(rounds=12) — the raw key is returned once at creation and never stored.
-- prefix (first 12 chars of the raw key) is stored for display and fast lookup only.
-- scopes is a comma-delimited list of ApiScope names (e.g. 'TRANSACTIONS_READ,ACCOUNTS_WRITE').
-- No FORCE ROW LEVEL SECURITY: the auth filter must read this table before tenant context
-- is established. The bcrypt hash is the security boundary, not row-level isolation.
CREATE TABLE api_keys (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL,
    key_hash    TEXT        NOT NULL,
    prefix      VARCHAR(12) NOT NULL,
    scopes      TEXT        NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at  TIMESTAMPTZ
);

CREATE INDEX idx_api_keys_tenant ON api_keys (tenant_id);
CREATE INDEX idx_api_keys_prefix ON api_keys (prefix);
