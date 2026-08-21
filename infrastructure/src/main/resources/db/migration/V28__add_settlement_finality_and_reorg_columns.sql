-- Chain-finality evidence and reorg-reversal marker columns for settlements.
-- Additive only — a reorg reversal never rewrites tx_hash/block_number/confirmed_at
-- on the original row; it only sets reversal_transaction_id/reorged_at alongside a
-- new REORGED status. See docs/reconciliation.md and ReorgReversalService.
ALTER TABLE settlements
    ADD COLUMN chain_key               TEXT,
    ADD COLUMN log_index               INTEGER,
    ADD COLUMN observed_block_height   BIGINT,
    ADD COLUMN confirmation_source     TEXT,
    ADD COLUMN confirmations_required  BIGINT,
    ADD COLUMN reversal_transaction_id UUID,
    ADD COLUMN reorged_at              TIMESTAMPTZ;

ALTER TABLE settlements
    ADD CONSTRAINT fk_settlements_reversal_transaction
        FOREIGN KEY (reversal_transaction_id, tenant_id) REFERENCES transactions (id, tenant_id);

-- Backs ReorgReversalService's lookup: the still-reversible settlement for a given
-- (txHash, logIndex) — excludes rows already REORGED so re-delivery of the same
-- removed:true webhook is a no-op.
CREATE INDEX idx_settlements_tx_hash_log_index_reversible
    ON settlements (tenant_id, tx_hash, log_index)
    WHERE matched_transaction_id IS NOT NULL AND status <> 'REORGED';

-- Backs SettlementFinalityPoller's sweep: WATCHING and webhook-sourced UNMATCHED rows for a
-- tenant+chain not yet finality-confirmed, ordered for cheap range scans by block number.
-- confirmed_at IS NULL (not status = 'WATCHING') is the partial predicate because both
-- qualifying statuses share that condition — see SettlementRepository.findPendingFinalitySweep.
-- settlements keeps FORCE ROW LEVEL SECURITY (V10) — the poller queries this per-tenant, not
-- as an unscoped cross-tenant read.
CREATE INDEX idx_settlements_pending_finality_tenant_chain_key
    ON settlements (tenant_id, chain_key, block_number)
    WHERE confirmed_at IS NULL;
