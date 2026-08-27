package finance.idem.infrastructure.service

import finance.idem.application.agentic.ExecuteWorkflowCommand
import finance.idem.application.agentic.RollbackWorkflowCommand
import finance.idem.application.agentic.WorkflowStepCommand
import finance.idem.application.ledger.JournalLineRequest
import finance.idem.application.reconciliation.ReorgReversalCommand
import finance.idem.application.reconciliation.ReorgReversalResult
import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.AgentContext
import finance.idem.core.agentic.PolicyRule
import finance.idem.core.agentic.StepStatus
import finance.idem.core.agentic.WorkflowStatus
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountType
import finance.idem.core.monetary.OnChainEntry
import finance.idem.infrastructure.compliance.ComplianceConfig
import finance.idem.infrastructure.compliance.ComplianceQueueRepositoryAdapter
import finance.idem.infrastructure.compliance.LgpdRetentionRepositoryAdapter
import finance.idem.infrastructure.persistence.AccountRepositoryAdapter
import finance.idem.infrastructure.persistence.PersistenceTestConfig
import finance.idem.infrastructure.persistence.TransactionRepositoryAdapter
import finance.idem.infrastructure.persistence.audit.AgentAuditRepositoryAdapter
import finance.idem.infrastructure.persistence.audit.AuditConfig
import finance.idem.infrastructure.persistence.audit.AuditRepositoryAdapter
import finance.idem.infrastructure.persistence.idempotency.PostgresIdempotencyStore
import finance.idem.infrastructure.persistence.outbox.WebhookOutboxRepositoryAdapter
import finance.idem.infrastructure.persistence.policy.PolicyRepositoryAdapter
import finance.idem.infrastructure.persistence.policy.SessionDebitAdapter
import finance.idem.infrastructure.persistence.reconciliation.SettlementRepositoryAdapter
import finance.idem.infrastructure.persistence.workflow.WorkflowPlanRepositoryAdapter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies, against a real Postgres instance, that [ReorgReversalService] and
 * [RollbackWorkflowService] cannot both compensate the same agent-originated on-chain
 * transaction (the double-reversal race described in ReorgReversalService's class doc), and
 * that a reorg reversal of an agent workflow step surfaces through the agent audit trail.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    ExecuteWorkflowService::class,
    RollbackWorkflowService::class,
    ReorgReversalService::class,
    AgentAuditRecorder::class,
    PostTransactionService::class,
    BasicReconciliationService::class,
    WorkflowPlanRepositoryAdapter::class,
    AgentAuditRepositoryAdapter::class,
    AuditConfig::class,
    AuditRepositoryAdapter::class,
    WebhookOutboxRepositoryAdapter::class,
    TransactionRepositoryAdapter::class,
    AccountRepositoryAdapter::class,
    PostgresIdempotencyStore::class,
    SettlementRepositoryAdapter::class,
    PersistenceTestConfig::class,
    ComplianceConfig::class,
    ComplianceQueueRepositoryAdapter::class,
    LgpdRetentionRepositoryAdapter::class,
    PolicyRepositoryAdapter::class,
    SessionDebitAdapter::class,
)
class ReorgReversalServiceIntegrationTest : PostgresServiceIntegrationTestBase() {
    @Autowired lateinit var executeService: ExecuteWorkflowService

    @Autowired lateinit var rollbackService: RollbackWorkflowService

    @Autowired lateinit var reorgService: ReorgReversalService

    @Autowired lateinit var accountAdapter: AccountRepositoryAdapter

    @Autowired lateinit var workflowPlanAdapter: WorkflowPlanRepositoryAdapter

    @Autowired lateinit var transactionAdapter: TransactionRepositoryAdapter

    @Autowired lateinit var policyRepository: PolicyRepositoryAdapter

    private val tenantId = TenantId.generate()
    private val agentCtx = AgentContext(agentId = "agent-it", sessionId = "sess-it", intent = "test")
    private val now = Instant.now()

    private var debitId: AccountId = AccountId.generate()
    private var creditId: AccountId = AccountId.generate()

    @BeforeEach
    fun setup() {
        debitId = AccountId.generate()
        creditId = AccountId.generate()
        accountAdapter.save(Account.create(debitId, tenantId, "Debit-Account", FiatCurrency.BRL, AccountType.ASSET, now, "test"))
        accountAdapter.save(Account.create(creditId, tenantId, "Credit-Account", FiatCurrency.BRL, AccountType.LIABILITY, now, "test"))
        policyRepository.save(tenantId, null, PolicyRule.MaxDebitPerSession(MonetaryAmount.of("99999")))
        entityManager.flush()
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun onChainStep(
        key: String,
        txHash: String,
        logIndex: Int,
        chainKey: String = "EVM_1",
    ): WorkflowStepCommand {
        val entry =
            OnChainEntry(
                amount = MonetaryAmount.of("100.000000"),
                token = StablecoinToken.USDC,
                chainId = ChainId.EVM,
                txHash = txHash,
                blockNumber = 100L,
                walletAddress = "0xWallet-$key",
                tokenContract = "0xContract",
            )
        return WorkflowStepCommand(
            idempotencyKey = key,
            description = key,
            lines =
                listOf(
                    JournalLineRequest(debitId, EntryType.DEBIT, entry),
                    JournalLineRequest(creditId, EntryType.CREDIT, entry),
                ),
            metadata = mapOf("chain_key" to chainKey, "log_index" to logIndex.toString()),
        )
    }

    private fun executeAndCommit(step: WorkflowStepCommand): WorkflowPlanId {
        val result =
            executeService
                .execute(ExecuteWorkflowCommand(tenantId, agentCtx, listOf(step), "integration-test"))
                .getOrThrow()
        entityManager.flush()
        entityManager.clear()
        return result
    }

    private fun rollbackCmd(planId: WorkflowPlanId) =
        RollbackWorkflowCommand(tenantId, agentCtx, planId, "operator rollback", "integration-test")

    private fun reorgCmd(
        txHash: String,
        logIndex: Int,
        chainKey: String = "EVM_1",
    ) = ReorgReversalCommand(tenantId, txHash, logIndex, chainKey, "chain reorg detected")

    private fun compensatingTransactionCount(originalTxId: UUID): Long =
        (
            entityManager
                .createNativeQuery(
                    "SELECT COUNT(*) FROM transactions WHERE tenant_id = ?::uuid AND idempotency_key IN (?, ?)",
                ).setParameter(1, tenantId.value.toString())
                .setParameter(2, "rollback:$originalTxId")
                .setParameter(3, "reorg-reversal:$originalTxId")
                .singleResult as Number
        ).toLong()

    // ── reorg reversal traced back to the agent workflow ────────────────────────

    @Test
    fun `reorg reversal marks the step REORGED and writes a CHAIN_REORG_REVERSAL audit event`() {
        val txHash = "0x" + UUID.randomUUID().toString().replace("-", "")
        val planId = executeAndCommit(onChainStep("reorg-s0", txHash, logIndex = 0))
        val originalTxId = workflowPlanAdapter.findById(planId, tenantId)!!.steps[0].transactionId!!

        val result = reorgService.execute(reorgCmd(txHash, logIndex = 0)).getOrThrow()
        entityManager.flush()
        entityManager.clear()

        assertTrue(result is ReorgReversalResult.Reversed)

        val plan = workflowPlanAdapter.findById(planId, tenantId)!!
        assertEquals(StepStatus.REORGED, plan.steps[0].status)
        assertNotNull(plan.steps[0].compensatingTransactionId)

        assertNotNull(transactionAdapter.findByIdempotencyKey("reorg-reversal:${originalTxId.value}", tenantId))

        @Suppress("UNCHECKED_CAST")
        val auditRows =
            entityManager
                .createNativeQuery(
                    "SELECT intent FROM agent_audit_events WHERE workflow_plan_id = ?::uuid AND intent = 'CHAIN_REORG_REVERSAL'",
                ).setParameter(1, planId.value.toString())
                .resultList as List<String>
        assertEquals(1, auditRows.size, "Expected exactly one CHAIN_REORG_REVERSAL audit event")
    }

    // ── double-reversal race: reorg first, then an operator rollback of the same plan ──

    @Test
    fun `step already reorged is skipped by a later rollback — no double compensation`() {
        val txHash = "0x" + UUID.randomUUID().toString().replace("-", "")
        val planId = executeAndCommit(onChainStep("race-a-s0", txHash, logIndex = 0))
        val originalTxId = workflowPlanAdapter.findById(planId, tenantId)!!.steps[0].transactionId!!

        reorgService.execute(reorgCmd(txHash, logIndex = 0)).getOrThrow()
        entityManager.flush()
        entityManager.clear()

        // Operator now tries to roll back the same (already reorged) plan.
        rollbackService.execute(rollbackCmd(planId)).getOrThrow()
        entityManager.flush()
        entityManager.clear()

        val plan = workflowPlanAdapter.findById(planId, tenantId)!!
        assertEquals(WorkflowStatus.ROLLED_BACK, plan.status, "Plan-level rollback still completes")
        assertEquals(StepStatus.REORGED, plan.steps[0].status, "Step stays REORGED — the rollback found nothing EXECUTED to compensate")

        // Exactly one compensating transaction for the original tx — the reorg's, never a rollback's.
        assertEquals(1L, compensatingTransactionCount(originalTxId.value))
        assertNull(transactionAdapter.findByIdempotencyKey("rollback:${originalTxId.value}", tenantId))
    }

    // ── double-reversal race: operator rollback first, then a reorg of the same original tx ──

    @Test
    fun `original tx already rolled back is reused by a later reorg — no double compensation`() {
        val txHash = "0x" + UUID.randomUUID().toString().replace("-", "")
        val planId = executeAndCommit(onChainStep("race-b-s0", txHash, logIndex = 0))
        val originalTxId = workflowPlanAdapter.findById(planId, tenantId)!!.steps[0].transactionId!!

        rollbackService.execute(rollbackCmd(planId)).getOrThrow()
        entityManager.flush()
        entityManager.clear()
        val rollbackTxId = transactionAdapter.findByIdempotencyKey("rollback:${originalTxId.value}", tenantId)!!.id

        // The chain reorg for the same original transfer is detected after the rollback already ran.
        val result = reorgService.execute(reorgCmd(txHash, logIndex = 0)).getOrThrow()
        entityManager.flush()
        entityManager.clear()

        assertTrue(result is ReorgReversalResult.AlreadyCompensatedByRollback)
        assertEquals(rollbackTxId, (result as ReorgReversalResult.AlreadyCompensatedByRollback).rollbackTransactionId)
        assertEquals(rollbackTxId, result.settlement.reversalTransactionId)

        // Still exactly one compensating transaction — the rollback's, never a second reorg one.
        assertEquals(1L, compensatingTransactionCount(originalTxId.value))
        assertNull(transactionAdapter.findByIdempotencyKey("reorg-reversal:${originalTxId.value}", tenantId))
    }
}
