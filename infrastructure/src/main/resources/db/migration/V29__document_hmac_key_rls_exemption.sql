-- V28 added hmac_key to tenants, which inherits V13's NO FORCE ROW LEVEL SECURITY
-- (the table-owner role bypasses RLS for cross-tenant reads such as
-- WebhookOutboxPoller resolving webhook_url/webhook_secret). That exemption now
-- also covers this per-tenant audit-signing key. This is an accepted,
-- defense-in-depth-only tradeoff -- RLS is the backstop, not the only control --
-- at the same exposure level webhook_secret has carried since V13. Documented
-- here (not fixed) per #284 review: a table split would be needed to isolate
-- hmac_key from this exemption, and isn't warranted without a driving incident.
COMMENT ON COLUMN tenants.hmac_key IS
    'Per-tenant HMAC key for AgentAuditEvent signing. Inherits this table''s '
    'NO FORCE ROW LEVEL SECURITY exemption (see V13) -- same accepted, '
    'defense-in-depth-only tradeoff already applied to webhook_secret.';
