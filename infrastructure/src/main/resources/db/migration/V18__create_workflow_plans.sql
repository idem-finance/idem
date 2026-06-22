-- Agent workflow plan aggregate. Tracks multi-step execution state so
-- RollbackWorkflowUseCase can locate and compensate committed steps.
-- FORCE RLS: same pattern as transactions and audit_log.

CREATE TABLE workflow_plans (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL,
    agent_id     TEXT        NOT NULL,
    session_id   TEXT        NOT NULL,
    intent       TEXT,
    status       TEXT        NOT NULL,
    occurred_at  TIMESTAMPTZ NOT NULL,
    committed_at TIMESTAMPTZ,
    CONSTRAINT chk_workflow_plans_status
        CHECK (status IN ('PLANNED', 'EXECUTING', 'COMMITTED', 'ROLLED_BACK'))
);

-- Composite unique index required for FK from workflow_plan_steps.
CREATE UNIQUE INDEX uq_workflow_plans_id_tenant ON workflow_plans (id, tenant_id);
CREATE INDEX idx_workflow_plans_tenant ON workflow_plans (tenant_id, occurred_at DESC);

ALTER TABLE workflow_plans ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_plans FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON workflow_plans
    FOR ALL
    USING      (tenant_id = current_setting('app.tenant_id', true)::UUID)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::UUID);

-- Steps record per-step execution state and the resulting transactionId for rollback.
CREATE TABLE workflow_plan_steps (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_plan_id UUID NOT NULL,
    tenant_id        UUID NOT NULL,
    step_index       INT  NOT NULL,
    idempotency_key  TEXT NOT NULL,
    status           TEXT NOT NULL DEFAULT 'PENDING',
    transaction_id   UUID,
    CONSTRAINT fk_workflow_plan_steps_plan
        FOREIGN KEY (workflow_plan_id, tenant_id)
        REFERENCES workflow_plans (id, tenant_id),
    CONSTRAINT chk_step_status
        CHECK (status IN ('PENDING', 'EXECUTED', 'FAILED')),
    CONSTRAINT uq_workflow_step_index
        UNIQUE (workflow_plan_id, step_index)
);

CREATE INDEX idx_workflow_plan_steps_plan ON workflow_plan_steps (workflow_plan_id);

ALTER TABLE workflow_plan_steps ENABLE ROW LEVEL SECURITY;
ALTER TABLE workflow_plan_steps FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON workflow_plan_steps
    FOR ALL
    USING      (tenant_id = current_setting('app.tenant_id', true)::UUID)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::UUID);

-- Prevent deletion of workflow plan rows so execution history is always recoverable.
-- Steps intentionally keep DELETE permission — the adapter deletes and re-inserts all
-- steps on every save() to work around JPA cascade ordering on the unique index.
REVOKE DELETE ON workflow_plans FROM PUBLIC;
