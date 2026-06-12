-- Supports keyset pagination for GET /api/v1/accounts/{id}/entries (#52):
-- ORDER BY created_at DESC, id DESC, filtered by account_id + tenant_id.
CREATE INDEX idx_journal_lines_account_created
    ON journal_lines (account_id, tenant_id, created_at DESC, id DESC);
