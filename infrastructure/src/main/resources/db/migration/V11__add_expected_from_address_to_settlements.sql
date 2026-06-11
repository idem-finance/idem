-- Optional sender address an operator expects an incoming transfer to come
-- from, registered alongside a PENDING Settlement (future
-- POST /accounts/{id}/settlements). When set and it matches the on-chain
-- transfer's fromAddress, BasicReconciliationService prefers this row over
-- amount+FIFO. NULL preserves today's amount+FIFO-only behavior.
ALTER TABLE settlements
    ADD COLUMN expected_from_address TEXT NULL;
