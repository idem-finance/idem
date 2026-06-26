CREATE TABLE travel_rule_data (
    id               UUID           NOT NULL DEFAULT gen_random_uuid(),
    tenant_id        UUID           NOT NULL,
    transfer_id      TEXT           NOT NULL,
    originator       JSONB          NOT NULL,
    beneficiary      JSONB          NOT NULL,
    transfer_amount  NUMERIC(38,18) NOT NULL,
    transfer_asset   TEXT           NOT NULL
        CHECK (transfer_asset IN ('USDC','USDT','BRZ','PYUSD')),
    threshold        NUMERIC(38,18) NOT NULL DEFAULT 1000,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT pk_travel_rule_data PRIMARY KEY (id)
);

CREATE UNIQUE INDEX uq_travel_rule_data_transfer_id_tenant
    ON travel_rule_data (transfer_id, tenant_id);

CREATE INDEX idx_travel_rule_data_tenant
    ON travel_rule_data (tenant_id, created_at DESC);

ALTER TABLE travel_rule_data ENABLE ROW LEVEL SECURITY;
ALTER TABLE travel_rule_data FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON travel_rule_data
    USING (tenant_id = current_setting('app.tenant_id', true)::UUID);

CREATE POLICY tenant_read ON travel_rule_data FOR SELECT
    USING (tenant_id = current_setting('app.tenant_id', true)::UUID);
