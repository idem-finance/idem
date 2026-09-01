-- Second extension of the "grows over time" `tenants` table (#272, internal admin
-- tenant-provisioning API), after V28's plan/limits/flags. Nullable: existing
-- self-hosted/dev-seeded tenants (DevDataSeeder's raw INSERT) have neither.
-- Required-ness for provisioned tenants is enforced at the request-DTO boundary,
-- not here.
ALTER TABLE tenants
    ADD COLUMN organization_name TEXT,
    ADD COLUMN contact_email TEXT;
