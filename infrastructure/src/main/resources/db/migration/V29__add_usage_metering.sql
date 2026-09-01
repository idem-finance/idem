-- Usage metering (#271): monthly limits on tenants, raw usage events, hourly rollup buckets,
-- and the rollup job's watermark. One migration for the whole feature -- these are a single
-- atomic storage design, always deployed and read together (mirrors V19/V20's bundling of
-- table+indexes+RLS).
--
-- Kept as its own migration rather than folded into V28: V28 belongs to the separately
-- reviewed, already-open PR for #274. Editing it from this stacked branch would couple
-- #271's review to that PR's file and create rebase friction.

-- Monthly usage limits for billing/self-serve visibility. NULL = unlimited, matching the
-- null-semantics of rate_limit_per_second/rate_limit_per_minute (V28).
ALTER TABLE tenants
    ADD COLUMN monthly_transaction_limit BIGINT,
    ADD COLUMN monthly_api_call_limit BIGINT,
    ADD COLUMN monthly_chain_event_limit BIGINT,
    ADD COLUMN monthly_webhook_delivery_limit BIGINT,
    ADD COLUMN monthly_entry_limit BIGINT;

-- Inherits tenants' existing NO FORCE ROW LEVEL SECURITY (V13) -- no new RLS statements needed.

-- Raw, append-only usage events. High write volume (every transaction commit, every chain
-- event, every flushed batch of API calls) -- BIGSERIAL PK, not gen_random_uuid(), since
-- these rows are never referenced by any other table.
--
-- idempotency_key is nullable and unconstrained by default so callers with no natural dedup
-- key (PostTransactionService's TRANSACTION_COUNT/ENTRY_COUNT, ApiCallCounterFlushJob's
-- API_CALL_COUNT) are unaffected -- the partial unique index only applies to rows that opt in
-- with a non-null key. Chain-event usage recording (ChainReaderOrchestrator,
-- AlchemyWebhookService, QuickNodeWebhookService) uses it to protect against double-counting
-- CHAIN_EVENT_COUNT on redelivery/retry of an already-processed transfer (crash before the
-- chain checkpoint advances, or at-least-once webhook redelivery) -- postTransactionUseCase's
-- own idempotency key (DetectedTransfer.idempotencyKey) already makes the ledger-side POST a
-- safe no-op, but usage_metrics needed its own.
CREATE TABLE usage_metrics (
    id              BIGSERIAL   PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    metric_type     TEXT        NOT NULL
        CHECK (metric_type IN ('TRANSACTION_COUNT', 'API_CALL_COUNT', 'CHAIN_EVENT_COUNT', 'WEBHOOK_DELIVERY_COUNT', 'ENTRY_COUNT')),
    amount          BIGINT      NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    idempotency_key TEXT
);

CREATE INDEX idx_usage_metrics_tenant_metric_time ON usage_metrics (tenant_id, metric_type, occurred_at);
-- Cross-tenant scan consumed by the hourly rollup job.
CREATE INDEX idx_usage_metrics_rollup_scan ON usage_metrics (occurred_at);
CREATE UNIQUE INDEX uq_usage_metrics_idempotency
    ON usage_metrics (tenant_id, metric_type, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

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

-- The hourly rollup job's cross-tenant batch INSERT (UsageMetricRepositoryAdapter.rollupHour)
-- has no policy under which to execute otherwise and is silently denied for any non-superuser
-- role under FORCE RLS. This INSERT-only policy allows that cross-tenant write while leaving
-- SELECT (and UPDATE/DELETE, revoked from PUBLIC below) tenant-scoped. Safe because the
-- tenant_id values written are computed by the rollup's own GROUP BY tenant_id over
-- already-tenant-scoped usage_metrics rows -- never user/request-supplied -- so there is no
-- cross-tenant injection vector via request input. A compromised connection could in principle
-- INSERT an arbitrary tenant_id, but that is the same trust boundary the app's DB role already
-- has for every other write in this schema, and it still cannot SELECT another tenant's rows
-- (tenant_read is unaffected).
--
-- Deliberately narrower than dropping to NO FORCE ROW LEVEL SECURITY (the pattern used for
-- webhook_outbox/V12, tenants/V13, and the raw usage_metrics table above): that would remove
-- RLS enforcement for reads too, which is unacceptable here since usage_metrics_hourly is
-- tenant-readable via the usage API (UsageMeteringService.getMonthlyUsage), unlike those
-- internal-only tables.
--
-- Follow-up: idem-finance/idem#286 -- the app's DB role is currently a Postgres superuser in
-- dev/test (compose.yaml, Testcontainers), which bypasses RLS entirely regardless of FORCE.
-- This policy is correct schema regardless, for a properly-scoped production role.
CREATE POLICY rollup_insert ON usage_metrics_hourly
    FOR INSERT
    WITH CHECK (true);

REVOKE UPDATE, DELETE ON usage_metrics_hourly FROM PUBLIC;

-- Single-row watermark for the rollup job. No tenant data -- no RLS.
CREATE TABLE usage_metrics_rollup_state (
    id                  SMALLINT    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    last_rolled_up_hour TIMESTAMPTZ NOT NULL
);

INSERT INTO usage_metrics_rollup_state (id, last_rolled_up_hour) VALUES (1, date_trunc('hour', now()));
