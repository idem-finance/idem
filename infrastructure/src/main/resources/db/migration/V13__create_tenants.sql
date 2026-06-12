-- Tenant registry (#55). Initially holds webhook delivery configuration
-- consumed by WebhookOutboxPoller. Grows over time (name, plan, etc.) as
-- tenant-management endpoints are added -- those endpoints are out of scope
-- here and would write their own row via SET LOCAL app.tenant_id.
CREATE TABLE tenants (
    id             UUID        PRIMARY KEY,
    webhook_url    TEXT,
    webhook_secret TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON tenants
    FOR ALL
    USING      (id = current_setting('app.tenant_id', true)::UUID)
    WITH CHECK (id = current_setting('app.tenant_id', true)::UUID);

-- NO FORCE: mirrors webhook_outbox (V12). WebhookOutboxPoller resolves each
-- dispatch row's destination/secret by tenant id while iterating cross-tenant
-- PENDING/FAILED rows, as the table-owner role with no app.tenant_id set. A
-- future tenant-facing settings endpoint reads/writes its own row via
-- SET LOCAL app.tenant_id, enforced by the tenant_isolation policy above.
ALTER TABLE tenants NO FORCE ROW LEVEL SECURITY;
