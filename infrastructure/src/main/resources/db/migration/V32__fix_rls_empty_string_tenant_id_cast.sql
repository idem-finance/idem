-- Every tenant-scoped RLS policy since V1 casts `current_setting('app.tenant_id', true)`
-- straight to ::UUID, on the assumption that an unset app.tenant_id reads back as SQL NULL
-- (NULL::UUID compares as UNKNOWN/false, correctly hiding every row). That assumption holds
-- the FIRST time a custom GUC is read on a given backend connection -- but Postgres registers
-- a placeholder for a custom parameter the first time it's ever SET on that connection, and
-- from then on an unset/reverted value reads back as an EMPTY STRING, not NULL. Verified
-- against a real postgres:16: `current_setting('app.tenant_id', true) IS NULL` is true before
-- any SET, but false (value '') on the same connection after a SET LOCAL ... COMMIT/ROLLBACK
-- takes it out of scope. `''::UUID` throws (invalid input syntax), not "no match".
--
-- This bug has existed since V1. It was invisible until V31, because every connection ran as
-- a superuser before that, and superusers bypass RLS unconditionally -- Postgres never even
-- evaluates a policy's USING/WITH CHECK expression for them, so the bad cast never ran.
-- Hikari pools and reuses connections, so in production this would have started throwing
-- "invalid input syntax for type uuid" on effectively random requests -- whichever ones
-- happened to land on a pooled connection that had previously handled a request with
-- app.tenant_id set -- the moment RLS actually started enforcing.
--
-- Fix: NULLIF(..., '') folds the empty-string case back to NULL before the cast, restoring
-- the "unset -> hide every row" behavior every one of these policies was actually written to
-- rely on. ALTER POLICY changes the USING/WITH CHECK expression on an existing policy without
-- touching its name, table, or command scope -- no need to DROP/CREATE anything.

ALTER POLICY tenant_isolation ON accounts
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

ALTER POLICY tenant_isolation ON transactions
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

ALTER POLICY tenant_isolation ON journal_lines
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

ALTER POLICY tenant_read ON audit_log
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);
ALTER POLICY tenant_insert ON audit_log
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

ALTER POLICY tenant_isolation ON webhook_outbox
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

ALTER POLICY tenant_isolation ON idempotency_keys
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

ALTER POLICY tenant_isolation ON settlements
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

-- tenants is keyed by id, not tenant_id.
ALTER POLICY tenant_isolation ON tenants
    USING      (id = NULLIF(current_setting('app.tenant_id', true), '')::UUID)
    WITH CHECK (id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

ALTER POLICY tenant_isolation ON workflow_plans
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

-- V20 renamed workflow_plan_steps -> workflow_steps; the policy (and its name,
-- tenant_isolation) carried over unchanged through that rename.
ALTER POLICY tenant_isolation ON workflow_steps
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

ALTER POLICY tenant_read ON agent_audit_events
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);
ALTER POLICY tenant_insert ON agent_audit_events
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

-- tenant_read was dropped by V22 as redundant with tenant_isolation (FOR ALL already
-- covers SELECT) -- only tenant_isolation exists to fix here.
ALTER POLICY tenant_isolation ON travel_rule_data
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

ALTER POLICY tenant_isolation ON compliance_queue
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

ALTER POLICY tenant_isolation ON lgpd_retention_schedule
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

ALTER POLICY tenant_isolation ON policy_rules
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

ALTER POLICY tenant_isolation ON settlement_idempotency_keys
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

ALTER POLICY tenant_all ON usage_metrics
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);

ALTER POLICY tenant_read ON usage_metrics_hourly
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::UUID);
