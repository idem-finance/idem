-- Transactional outbox for webhook delivery.
-- WebhookOutboxPoller (@Scheduled every 5s) reads undispatched rows and delivers them.
CREATE TABLE webhook_outbox (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL,
    transaction_id UUID        NOT NULL,
    event_type     TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    dispatched     BOOLEAN     NOT NULL DEFAULT false,
    retry_count    INT         NOT NULL DEFAULT 0,
    last_error     TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    dispatched_at  TIMESTAMPTZ
);

-- Poller query: undispatched rows ordered by creation time, scoped to tenant
CREATE INDEX idx_webhook_outbox_pending ON webhook_outbox (tenant_id, dispatched, created_at)
    WHERE dispatched = false;

ALTER TABLE webhook_outbox ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON webhook_outbox
    FOR ALL
    USING      (tenant_id = current_setting('app.tenant_id', true)::UUID)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::UUID);
