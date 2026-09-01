-- usage_metrics_hourly has FORCE RLS with only a tenant-scoped SELECT policy (V30) -- the
-- hourly rollup job's cross-tenant batch INSERT (UsageMetricRepositoryAdapter.rollupHour)
-- has no policy under which to execute and is silently denied for any non-superuser role.
--
-- This INSERT-only policy allows the rollup's cross-tenant write while leaving SELECT
-- (and UPDATE/DELETE, already revoked from PUBLIC in V30) tenant-scoped. Safe because the
-- tenant_id values written are computed by the rollup's own GROUP BY tenant_id over
-- already-tenant-scoped usage_metrics rows -- never user/request-supplied -- so there is no
-- cross-tenant injection vector via request input. A compromised connection could in
-- principle INSERT an arbitrary tenant_id, but that is the same trust boundary the app's DB
-- role already has for every other write in this schema, and it still cannot SELECT another
-- tenant's rows (tenant_read is unaffected).
--
-- This is deliberately narrower than dropping to NO FORCE ROW LEVEL SECURITY (the pattern
-- used for webhook_outbox/V12, tenants/V13, and the raw usage_metrics table itself in V30):
-- that would remove RLS enforcement for reads too, which is unacceptable here because
-- usage_metrics_hourly is tenant-readable via the usage API (UsageMeteringService.
-- getMonthlyUsage), unlike those internal-only tables.
--
-- Follow-up: idem-finance/idem#286 -- the app's DB role is currently a Postgres superuser in
-- dev/test (compose.yaml, Testcontainers), which bypasses RLS entirely regardless of FORCE.
-- This policy is correct schema regardless, for a properly-scoped production role.
CREATE POLICY rollup_insert ON usage_metrics_hourly
    FOR INSERT
    WITH CHECK (true);
