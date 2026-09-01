-- Adds optional per-event dedup for usage_metrics. Chain-event usage recording
-- (ChainReaderOrchestrator, AlchemyWebhookService, QuickNodeWebhookService) has no
-- idempotency protection today: on any redelivery/retry of an already-processed transfer
-- (crash before the chain checkpoint advances, or at-least-once webhook redelivery),
-- CHAIN_EVENT_COUNT is recorded again even though postTransactionUseCase's own idempotency
-- key (DetectedTransfer.idempotencyKey) makes the ledger-side POST a safe no-op.
--
-- Nullable and unconstrained by default so callers with no natural dedup key
-- (PostTransactionService's TRANSACTION_COUNT/ENTRY_COUNT, ApiCallCounterFlushJob's
-- API_CALL_COUNT) are unaffected — the partial unique index only applies to rows that
-- opt in with a non-null key.
ALTER TABLE usage_metrics ADD COLUMN idempotency_key TEXT;

CREATE UNIQUE INDEX uq_usage_metrics_idempotency
    ON usage_metrics (tenant_id, metric_type, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
