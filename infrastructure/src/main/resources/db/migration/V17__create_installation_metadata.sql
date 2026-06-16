-- Anonymous installation ID for telemetry (issue #106).
-- No RLS: instance-global metadata, not tenant-scoped.
-- Singleton PK (constant value 1) prevents duplicate rows from concurrent cold-start replicas.
CREATE TABLE installation_metadata (
    singleton  INT         NOT NULL DEFAULT 1,
    id         UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_installation_metadata  PRIMARY KEY (singleton),
    CONSTRAINT chk_installation_metadata_singleton CHECK (singleton = 1)
);
