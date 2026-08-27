-- Backs WorkflowPlanRepository.findByTransactionId — ReorgReversalService tracing a reorg
-- reversal back to the agent workflow step that posted the original transaction.
CREATE INDEX idx_workflow_steps_transaction_id ON workflow_steps (transaction_id);

-- REORGED is distinct from ROLLED_BACK: the step's on-chain settlement was invalidated by a
-- chain reorg (ReorgReversalService), not compensated by an operator/agent-initiated rollback.
ALTER TABLE workflow_steps DROP CONSTRAINT chk_step_status;
ALTER TABLE workflow_steps ADD CONSTRAINT chk_step_status
    CHECK (status IN ('PENDING', 'EXECUTED', 'ROLLED_BACK', 'FAILED', 'REORGED'));
