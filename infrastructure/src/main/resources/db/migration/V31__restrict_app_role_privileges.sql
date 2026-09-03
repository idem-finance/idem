-- Closes idem#286: the role Flyway/the app connects as (`idem` in compose.yaml,
-- whatever a given deployment names it in idem-infra/Testcontainers) is the Postgres
-- *bootstrap* superuser -- the very role initdb creates. Postgres refuses to ever
-- strip SUPERUSER from that specific role (`ALTER ROLE <bootstrap> NOSUPERUSER`
-- fails with "permission denied to alter role / the bootstrap user must have the
-- SUPERUSER attribute", verified against a real Testcontainers postgres:16 while
-- building this migration), so RLS can never be enforced against the connecting
-- role directly. It has to run AS a genuinely non-superuser role instead.
--
-- idem_app is that role: NOLOGIN (nobody authenticates as it directly -- no password
-- to manage, nothing for idem-infra to rotate), NOSUPERUSER, NOBYPASSRLS. The
-- connecting role (still `idem`, unchanged everywhere -- compose.yaml, Testcontainers,
-- Cloud SQL) is granted membership and assumes it per-transaction via
-- `SET LOCAL ROLE idem_app` in EntityManagerExtensions.setRlsTenantId, the same
-- helper that already sets `app.tenant_id` on every repository-adapter call --
-- reusing that single call site rather than touching every adapter, and
-- transaction-scoped (SET LOCAL) so it reverts automatically at commit, exactly like
-- app.tenant_id already does. Once `current_user` is idem_app for that transaction,
-- superuser()/RLS-owner checks evaluate against idem_app's (non-super, non-owner)
-- attributes, not idem's -- this is standard, documented Postgres behavior for
-- SET ROLE, not something idem_app-specific.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'idem_app') THEN
        CREATE ROLE idem_app NOLOGIN NOSUPERUSER NOBYPASSRLS;
    END IF;
    EXECUTE format('GRANT idem_app TO %I', current_user);
END
$$;

-- idem_app is not the owner of any table, so unlike the connecting role it's fully
-- subject to RLS everywhere, FORCE or not -- these grants are the privilege layer;
-- RLS policies are the row-visibility layer underneath them.
--
-- USAGE ON SCHEMA public is NOT implicitly granted to a freshly created role on this
-- database (verified empirically -- has_schema_privilege('idem_app','public','USAGE')
-- is false without this line, and every query fails "permission denied for schema
-- public" / "relation ... does not exist" despite the table grants below).
GRANT USAGE ON SCHEMA public TO idem_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO idem_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO idem_app;

-- Applies to tables/sequences future migrations create (all run as the same
-- connecting role) so idem_app doesn't need a matching grant added by hand every
-- time -- except FORCE-vs-NO-FORCE table design, which still needs a per-table
-- decision same as today.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO idem_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO idem_app;

-- tenants (V13), webhook_outbox (V12) and usage_metrics (V29) are NO FORCE ROW LEVEL
-- SECURITY, and lgpd_retention_schedule (V24) was never explicitly FORCE'd either way --
-- all four have relied on table-OWNER bypass for their one legitimate cross-tenant SELECT
-- each (WebhookOutboxPoller resolving per-tenant webhook config across all tenants and
-- scanning cross-tenant dispatchable rows; UsageMetricsRollupJob's rollupHour aggregating
-- usage_metrics across all tenants; LgpdRetentionService's monthly sweep finding every
-- tenant's expired rows). idem_app is not the owner of anything, so that bypass doesn't
-- apply to it -- replace it with an explicit, SELECT-only, idem_app-scoped policy instead
-- of transferring ownership. SELECT-only (not FOR ALL) deliberately: every write path on
-- these tables already sets app.tenant_id (TenantRepositoryAdapter,
-- WebhookOutboxRepositoryAdapter, UsageMetricRepositoryAdapter.recordEvent,
-- LgpdRetentionRepositoryAdapter/TravelRuleRepositoryAdapter for the sweep's own deletes),
-- so INSERT/UPDATE/DELETE stay governed by the existing tenant-scoped policy alone -- this
-- is in fact *stricter* than the old owner-bypass, which exempted every command, not just
-- SELECT. None of these four cross-tenant reads call setRlsTenantId today, so in practice
-- they keep running as the connecting/bootstrap role (still bypasses RLS via SUPERUSER,
-- unchanged from before this migration) -- these policies are the correct schema for if
-- that ever changes, not currently load-bearing.
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;
CREATE POLICY service_cross_tenant_read ON tenants FOR SELECT TO idem_app USING (true);

ALTER TABLE webhook_outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY service_cross_tenant_read ON webhook_outbox FOR SELECT TO idem_app USING (true);

ALTER TABLE usage_metrics FORCE ROW LEVEL SECURITY;
CREATE POLICY service_cross_tenant_read ON usage_metrics FOR SELECT TO idem_app USING (true);

ALTER TABLE lgpd_retention_schedule FORCE ROW LEVEL SECURITY;
CREATE POLICY service_cross_tenant_read ON lgpd_retention_schedule FOR SELECT TO idem_app USING (true);

-- usage_metrics_hourly (V29) already has an unconditional INSERT policy (rollup_insert) for
-- the same rollup job, but that alone isn't enough: `ON CONFLICT (...) DO NOTHING`'s arbiter
-- index check needs to SELECT the potentially-conflicting row to know whether to skip it, and
-- that SELECT is subject to RLS too. With only tenant_read (tenant-scoped) available, a
-- multi-tenant batch INSERT ... SELECT ... ON CONFLICT DO NOTHING errors outright instead of
-- silently skipping (reproduced empirically while building this migration -- the same
-- statement succeeds with ON CONFLICT removed, or with this policy added). Needed even though
-- setRlsTenantId is called earlier in the same transaction for unrelated per-tenant writes,
-- which is what actually put idem_app in this position for rollupHour's cross-tenant batch.
CREATE POLICY service_cross_tenant_read ON usage_metrics_hourly FOR SELECT TO idem_app USING (true);
