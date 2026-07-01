package finance.idem.infrastructure.service

import finance.idem.application.agentic.ExecuteWorkflowCommand
import finance.idem.application.agentic.RollbackWorkflowCommand
import finance.idem.application.agentic.WorkflowStepCommand
import finance.idem.application.ledger.JournalLineRequest
import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.TenantId
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.AgentContext
import finance.idem.core.agentic.PolicyRule
import finance.idem.core.agentic.StepStatus
import finance.idem.core.agentic.WorkflowStatus
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountType
import finance.idem.core.monetary.FiatEntry
import finance.idem.infrastructure.compliance.ComplianceConfig
import finance.idem.infrastructure.compliance.ComplianceQueueRepositoryAdapter
import finance.idem.infrastructure.persistence.AccountRepositoryAdapter
import finance.idem.infrastructure.persistence.PersistenceTestConfig
import finance.idem.infrastructure.persistence.TransactionRepositoryAdapter
import finance.idem.infrastructure.persistence.audit.AgentAuditRepositoryAdapter
import finance.idem.infrastructure.persistence.audit.AuditConfig
import finance.idem.infrastructure.persistence.audit.AuditRepositoryAdapter
import finance.idem.infrastructure.persistence.idempotency.PostgresIdempotencyStore
import finance.idem.infrastructure.persistence.outbox.WebhookOutboxJpaRepository
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    ExecuteWorkflowService::class,
    RollbackWorkflowService::class,
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
    finance.idem.infrastructure.compliance.LgpdRetentionRepositoryAdapter::class,
    PolicyRepositoryAdapter::class,
    SessionDebitAdapter::class,
)
class RollbackWorkflowServiceIntegrationTest : PostgresServiceIntegrationTestBase() {
    @Autowired lateinit var executeService: ExecuteWorkflowService

    @Autowired lateinit var rollbackService: RollbackWorkflowService

    @Autowired lateinit var accountAdapter: AccountRepositoryAdapter

    @Autowired lateinit var workflowPlanAdapter: WorkflowPlanRepositoryAdapter

    @Autowired lateinit var transactionAdapter: TransactionRepositoryAdapter

    @Autowired lateinit var outboxJpaRepo: WebhookOutboxJpaRepository

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

    private fun brlLine(
        accountId: AccountId,
        entryType: EntryType,
    ) = JournalLineRequest(
        accountId = accountId,
        entryType = entryType,
        monetaryEntry = FiatEntry(MonetaryAmount.of("500.00"), FiatCurrency.BRL, PaymentRail.PIX),
    )

    private fun buildStep(
        key: String,
        debit: AccountId = debitId,
        credit: AccountId = creditId,
    ) = WorkflowStepCommand(
        idempotencyKey = key,
        description = key,
        lines = listOf(brlLine(debit, EntryType.DEBIT), brlLine(credit, EntryType.CREDIT)),
    )

    private fun buildCmd(steps: List<WorkflowStepCommand>) = ExecuteWorkflowCommand(tenantId, agentCtx, steps, "integration-test")

    private fun rollbackCmd(
        planId: WorkflowPlanId,
        reason: String = "test-rollback",
    ) = RollbackWorkflowCommand(tenantId, agentCtx, planId, reason, "integration-test")

    private fun executeAndCommit(steps: List<WorkflowStepCommand>): WorkflowPlanId {
        val result = executeService.execute(buildCmd(steps)).getOrThrow()
        entityManager.flush()
        entityManager.clear()
        return result
    }

    // ── reverse-order rollback ────────────────────────────────────────────────

    @Test
    fun `rolls back all steps in reverse order`() {
        val planId = executeAndCommit(listOf(buildStep("s0"), buildStep("s1")))
        val originalPlan = workflowPlanAdapter.findById(planId, tenantId)!!
        val tx0 = originalPlan.steps[0].transactionId!!
        val tx1 = originalPlan.steps[1].transactionId!!

        rollbackService.execute(rollbackCmd(planId)).getOrThrow()
        entityManager.flush()
        entityManager.clear()

        val rolledBack = workflowPlanAdapter.findById(planId, tenantId)!!
        assertEquals(WorkflowStatus.ROLLED_BACK, rolledBack.status)
        assertNotNull(rolledBack.rolledBackAt)
        assertEquals("test-rollback", rolledBack.rollbackReason)

        // Both steps marked ROLLED_BACK with compensating transaction IDs set
        rolledBack.steps.forEach { step ->
            assertEquals(StepStatus.ROLLED_BACK, step.status)
            assertNotNull(step.compensatingTransactionId)
        }

        // Compensating transactions created with idempotency key "rollback:{originalTxId}"
        assertNotNull(transactionAdapter.findByIdempotencyKey("rollback:${tx0.value}", tenantId))
        assertNotNull(transactionAdapter.findByIdempotencyKey("rollback:${tx1.value}", tenantId))
    }

    // ── compensating lines balance ────────────────────────────────────────────

    @Test
    fun `compensating transactions swap DEBIT and CREDIT on original lines`() {
        val planId = executeAndCommit(listOf(buildStep("comp-s0")))
        val originalTxId = workflowPlanAdapter.findById(planId, tenantId)!!.steps[0].transactionId!!

        rollbackService.execute(rollbackCmd(planId)).getOrThrow()
        entityManager.flush()
        entityManager.clear()

        val compTx = transactionAdapter.findByIdempotencyKey("rollback:${originalTxId.value}", tenantId)
        assertNotNull(compTx)

        // Original step: debitId was DEBIT, creditId was CREDIT
        // Compensating step: debitId must now be CREDIT, creditId must now be DEBIT
        val debitAccLine = compTx.lines.first { it.accountId == debitId }
        val creditAccLine = compTx.lines.first { it.accountId == creditId }
        assertEquals(EntryType.CREDIT, debitAccLine.entryType, "Original DEBIT account gets CREDIT in compensation")
        assertEquals(EntryType.DEBIT, creditAccLine.entryType, "Original CREDIT account gets DEBIT in compensation")
    }

    // ── audit events ──────────────────────────────────────────────────────────

    @Test
    fun `AgentAuditEvent written for rollback — PENDING before, COMPLETED after`() {
        val planId = executeAndCommit(listOf(buildStep("audit-s0")))

        rollbackService.execute(rollbackCmd(planId, "compliance audit")).getOrThrow()
        entityManager.flush()

        // execute() writes: PENDING + COMPLETED (2 events)
        // rollback() writes: PENDING (intent=ROLLBACK) + COMPLETED (intent=ROLLBACK) (2 more events)
        @Suppress("UNCHECKED_CAST")
        val rows =
            entityManager
                .createNativeQuery(
                    "SELECT status, intent FROM agent_audit_events WHERE workflow_plan_id = ?::uuid ORDER BY occurred_at",
                ).setParameter(1, planId.value.toString())
                .resultList as List<Array<Any?>>

        assertEquals(4, rows.size, "Expected 4 audit events: 2 from execute, 2 from rollback")
        assertEquals("PENDING", rows[0][0])
        assertEquals("COMPLETED", rows[1][0])
        assertEquals("PENDING", rows[2][0])
        assertEquals("ROLLBACK", rows[2][1], "Rollback PENDING audit event must have intent=ROLLBACK")
        assertEquals("COMPLETED", rows[3][0])
        assertEquals("ROLLBACK", rows[3][1], "Rollback COMPLETED audit event must have intent=ROLLBACK")
    }

    // ── outbox entry ──────────────────────────────────────────────────────────

    @Test
    fun `webhook_outbox row written with workflow_rolled_back event type`() {
        val planId = executeAndCommit(listOf(buildStep("outbox-s0")))

        rollbackService.execute(rollbackCmd(planId)).getOrThrow()
        entityManager.flush()

        assertEquals(1L, outboxCount("workflow.committed"), "execute() must produce workflow.committed")
        assertEquals(1L, outboxCount("workflow.rolled_back"), "rollback() must produce workflow.rolled_back")
        assertEquals(0L, outboxCount("workflow.failed"))
    }
}
