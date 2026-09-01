-- Monthly usage limits for billing/self-serve visibility (#271). NULL = unlimited,
-- matching the null-semantics of rate_limit_per_second/rate_limit_per_minute (V28).
--
-- Kept as its own migration rather than folded into V28: V28 belongs to the separately
-- reviewed, already-open PR for #274. Editing it from this stacked branch would couple
-- #271's review to that PR's file and create rebase friction.
ALTER TABLE tenants
    ADD COLUMN monthly_transaction_limit BIGINT,
    ADD COLUMN monthly_api_call_limit BIGINT,
    ADD COLUMN monthly_chain_event_limit BIGINT,
    ADD COLUMN monthly_webhook_delivery_limit BIGINT,
    ADD COLUMN monthly_entry_limit BIGINT;

-- Inherits tenants' existing NO FORCE ROW LEVEL SECURITY (V13) -- no new RLS statements needed.
