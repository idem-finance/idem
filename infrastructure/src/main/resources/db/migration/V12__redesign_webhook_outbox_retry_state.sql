-- Redesign webhook_outbox for the PENDING/DELIVERED/FAILED/DEAD retry state machine
-- specified in #54, replacing the binary dispatched/retry_count/dispatched_at columns.
ALTER TABLE webhook_outbox
    ADD COLUMN status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    ADD COLUMN attempts      INT          NOT NULL DEFAULT 0,
    ADD COLUMN next_retry_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ADD COLUMN delivered_at  TIMESTAMPTZ;

-- Backfill from the columns being dropped
UPDATE webhook_outbox SET status = 'DELIVERED', delivered_at = dispatched_at WHERE dispatched = true;
UPDATE webhook_outbox SET attempts = retry_count WHERE retry_count > 0;

DROP INDEX idx_webhook_outbox_pending;

ALTER TABLE webhook_outbox
    DROP COLUMN dispatched,
    DROP COLUMN retry_count,
    DROP COLUMN dispatched_at;

ALTER TABLE webhook_outbox
    ADD CONSTRAINT chk_webhook_outbox_status CHECK (status IN ('PENDING','DELIVERED','FAILED','DEAD'));

-- Tenant-scoped lookups
CREATE INDEX idx_webhook_outbox_tenant_status ON webhook_outbox (tenant_id, status, created_at);

-- Cross-tenant dispatchable batch (consumed by #55's WebhookOutboxPoller)
CREATE INDEX idx_webhook_outbox_dispatchable ON webhook_outbox (status, next_retry_at)
    WHERE status IN ('PENDING', 'FAILED');

-- #55's poller must read/update PENDING/FAILED rows across ALL tenants in one query, which
-- FORCE RLS makes impossible (current_setting('app.tenant_id') is unset -> zero rows for the
-- owner role). Drop FORCE while keeping RLS enabled, mirroring the api_keys precedent (V8:
-- "the auth filter must read this table before tenant context is established"). Tenant-scoped
-- reads/writes continue to work via SET LOCAL app.tenant_id for defense-in-depth; only the
-- owner role gains cross-tenant visibility on this operational queue table (no tenant-facing
-- read endpoint exists for it).
ALTER TABLE webhook_outbox NO FORCE ROW LEVEL SECURITY;
