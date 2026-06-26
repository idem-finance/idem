CREATE TABLE compliance_queue (
    id              UUID           NOT NULL DEFAULT gen_random_uuid(),
    tenant_id       UUID           NOT NULL,
    tx_hash         TEXT           NOT NULL,
    chain_id        TEXT           NOT NULL,
    entry_amount    NUMERIC(38,18) NOT NULL,
    reason          TEXT           NOT NULL
        CHECK (reason IN ('MISSING_DATA', 'INCOMPLETE_DATA')),
    missing_fields  JSONB          NOT NULL DEFAULT '[]',
    status          TEXT           NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'REVIEWED', 'CLEARED')),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT pk_compliance_queue PRIMARY KEY (id)
);

CREATE INDEX idx_compliance_queue_tenant ON compliance_queue (tenant_id, created_at DESC);

ALTER TABLE compliance_queue ENABLE ROW LEVEL SECURITY;
ALTER TABLE compliance_queue FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON compliance_queue
    USING (tenant_id = current_setting('app.tenant_id', true)::UUID);
