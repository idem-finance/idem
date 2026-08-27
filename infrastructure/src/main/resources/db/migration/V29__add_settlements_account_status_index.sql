-- Backs SettlementRepository.findByAccountIdAndStatus — the get_balance/describe_account
-- pending-finality breakdown (status = WATCHING) queried per account.
CREATE INDEX idx_settlements_account_status
    ON settlements (tenant_id, account_id, status);
