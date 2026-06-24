-- Enhance workflow_plans: rename timestamp columns, add rollback tracking fields,
-- widen status check to include FAILED.
ALTER TABLE workflow_plans RENAME COLUMN occurred_at  TO created_at;
ALTER TABLE workflow_plans RENAME COLUMN committed_at TO completed_at;

ALTER TABLE workflow_plans ADD COLUMN rolled_back_at  TIMESTAMPTZ;
ALTER TABLE workflow_plans ADD COLUMN rollback_reason TEXT;

ALTER TABLE workflow_plans DROP CONSTRAINT chk_workflow_plans_status;
ALTER TABLE workflow_plans ADD CONSTRAINT chk_workflow_plans_status
    CHECK (status IN ('PLANNED', 'EXECUTING', 'COMMITTED', 'ROLLED_BACK', 'FAILED'));

-- Rename workflow_plan_steps → workflow_steps and update its schema:
-- step_index → step_order, idempotency_key → description (human-readable label),
-- add execution timestamp and compensating transaction link for rollback tracking.
ALTER TABLE workflow_plan_steps RENAME TO workflow_steps;
ALTER INDEX IF EXISTS idx_workflow_plan_steps_plan RENAME TO idx_workflow_steps_plan;
ALTER INDEX IF EXISTS uq_workflow_step_index RENAME TO uq_workflow_step_order;

ALTER TABLE workflow_steps RENAME COLUMN step_index      TO step_order;
ALTER TABLE workflow_steps RENAME COLUMN idempotency_key TO description;

ALTER TABLE workflow_steps ADD COLUMN executed_at                  TIMESTAMPTZ;
ALTER TABLE workflow_steps ADD COLUMN compensating_transaction_id  UUID;

ALTER TABLE workflow_steps DROP CONSTRAINT chk_step_status;
ALTER TABLE workflow_steps ADD CONSTRAINT chk_step_status
    CHECK (status IN ('PENDING', 'EXECUTED', 'ROLLED_BACK', 'FAILED'));
