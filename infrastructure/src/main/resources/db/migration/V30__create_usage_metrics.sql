-- Usage metering (#271): raw events, hourly rollup buckets, and the rollup job's watermark.
-- One migration for all three -- they're a single atomic storage design for one feature,
-- always deployed and read together (mirrors V19/V20's bundling of table+indexes+RLS).

-- Raw, append-only usage events. High write volume (every transaction commit, every chain
-- event, every flushed batch of API calls) -- BIGSERIAL PK, not gen_random_uuid(), since
-- these rows are never referenced by any other table.
CREATE TABLE usage_metrics (
    id          BIGSERIAL   PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    metric_type TEXT        NOT NULL
        CHECK (metric_type IN ('TRANSACTION_COUNT', 'API_CALL_COUNT', 'CHAIN_EVENT_COUNT', 'WEBHOOK_DELIVERY_COUNT', 'ENTRY_COUNT')),
    amount      BIGINT      NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_usage_metrics_tenant_metric_time ON usage_metrics (tenant_id, metric_type, occurred_at);
-- Cross-tenant scan consumed by the hourly rollup job.
CREATE INDEX idx_usage_metrics_rollup_scan ON usage_metrics (occurred_at);

ALTER TABLE usage_metrics ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_all ON usage_metrics
    FOR ALL
    USING      (tenant_id = current_setting('app.tenant_id', true)::UUID)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::UUID);

-- NO FORCE: the hourly rollup job must read across ALL tenants in one query to aggregate
-- into usage_metrics_hourly, which FORCE RLS makes impossible for the owner role (same
-- exemption rationale as webhook_outbox, V12). Tenant-scoped reads/writes continue to work
-- via SET LOCAL app.tenant_id for defense-in-depth.
ALTER TABLE usage_metrics NO FORCE ROW LEVEL SECURITY;

-- Hourly rollup buckets. Only ever read per-tenant via the usage API -- FORCE RLS, same
-- security model as agent_audit_events (V19).
CREATE TABLE usage_metrics_hourly (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL,
    metric_type  TEXT        NOT NULL
        CHECK (metric_type IN ('TRANSACTION_COUNT', 'API_CALL_COUNT', 'CHAIN_EVENT_COUNT', 'WEBHOOK_DELIVERY_COUNT', 'ENTRY_COUNT')),
    value        BIGINT      NOT NULL,
    period_start TIMESTAMPTZ NOT NULL,
    period_end   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_usage_metrics_hourly UNIQUE (tenant_id, metric_type, period_start)
);

CREATE INDEX idx_usage_metrics_hourly_tenant_period ON usage_metrics_hourly (tenant_id, metric_type, period_start);

ALTER TABLE usage_metrics_hourly ENABLE ROW LEVEL SECURITY;
ALTER TABLE usage_metrics_hourly FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_read ON usage_metrics_hourly
    FOR SELECT
    USING (tenant_id = current_setting('app.tenant_id', true)::UUID);

REVOKE UPDATE, DELETE ON usage_metrics_hourly FROM PUBLIC;

-- Single-row watermark for the rollup job. No tenant data -- no RLS.
CREATE TABLE usage_metrics_rollup_state (
    id                  SMALLINT    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    last_rolled_up_hour TIMESTAMPTZ NOT NULL
);

INSERT INTO usage_metrics_rollup_state (id, last_rolled_up_hour) VALUES (1, date_trunc('hour', now()));
