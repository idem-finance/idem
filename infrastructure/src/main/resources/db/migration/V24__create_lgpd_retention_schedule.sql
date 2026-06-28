CREATE TABLE lgpd_retention_schedule (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL,
    entity_type     TEXT        NOT NULL,
    entity_id       TEXT        NOT NULL,
    retention_years INT         NOT NULL,
    scheduled_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deletion_due_at TIMESTAMPTZ NOT NULL,
    processed_at    TIMESTAMPTZ,
    PRIMARY KEY (id)
);

ALTER TABLE lgpd_retention_schedule ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON lgpd_retention_schedule
    USING (tenant_id = current_setting('app.tenant_id')::uuid);

CREATE INDEX lgpd_retention_schedule_due_idx
    ON lgpd_retention_schedule (deletion_due_at)
    WHERE processed_at IS NULL;
