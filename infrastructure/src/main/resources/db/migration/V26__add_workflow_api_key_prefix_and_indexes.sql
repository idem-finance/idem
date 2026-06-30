-- Add api_key_prefix to workflow_plans so per-agent hourly debit totals can be computed
-- without cross-agent bleed. Nullable: existing rows (pre-migration) will have NULL,
-- and sumDebitsLastHour treats NULL as "no key filter" for backward compatibility.
ALTER TABLE workflow_plans ADD COLUMN api_key_prefix VARCHAR(12);

-- Index for per-agent hourly debit aggregation (sumDebitsLastHour).
CREATE INDEX idx_workflow_plans_tenant_api_key ON workflow_plans (tenant_id, api_key_prefix);

-- Index for session-based debit aggregation (sumDebitsForSession join path).
CREATE INDEX idx_workflow_plans_tenant_session ON workflow_plans (tenant_id, session_id);

-- Index for PolicyGuard hot path: sumDebitsLastHour scans journal_lines filtered on
-- (tenant_id, entry_type, created_at). Existing indexes lead on account_id and are useless here.
CREATE INDEX idx_journal_lines_tenant_type_created ON journal_lines (tenant_id, entry_type, created_at DESC);
