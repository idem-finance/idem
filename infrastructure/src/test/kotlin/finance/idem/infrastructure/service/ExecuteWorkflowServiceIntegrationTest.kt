package finance.idem.infrastructure.service

import finance.idem.application.agentic.ExecuteWorkflowCommand
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
import finance.idem.core.agentic.PolicyViolationException
import finance.idem.core.agentic.WorkflowStatus
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountType
import finance.idem.core.monetary.FiatEntry
import finance.idem.infrastructure.persistence.AccountRepositoryAdapter
import finance.idem.infrastructure.persistence.PersistenceTestConfig
import finance.idem.infrastructure.persistence.TransactionRepositoryAdapter
import finance.idem.infrastructure.persistence.audit.AgentAuditRepositoryAdapter
import finance.idem.infrastructure.persistence.audit.AuditConfig
import finance.idem.infrastructure.persistence.audit.AuditRepositoryAdapter
import finance.idem.infrastructure.persistence.idempotency.PostgresIdempotencyStore
import finance.idem.infrastructure.persistence.outbox.WebhookOutboxJpaRepository
import finance.idem.infrastructure.persistence.outbox.WebhookOutboxRepositoryAdapter
import finance.idem.infrastructure.persistence.reconciliation.SettlementRepositoryAdapter
import finance.idem.infrastructure.persistence.workflow.WorkflowPlanRepositoryAdapter
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(
    ExecuteWorkflowService::class,
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
)
class ExecuteWorkflowServiceIntegrationTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:16")
            .withDatabaseName("idem_test")
            .withUsername("idem")
            .withPassword("idem")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired lateinit var executeService: ExecuteWorkflowService
    @Autowired lateinit var accountAdapter: AccountRepositoryAdapter
    @Autowired lateinit var workflowPlanAdapter: WorkflowPlanRepositoryAdapter
    @Autowired lateinit var transactionAdapter: TransactionRepositoryAdapter
    @Autowired lateinit var outboxJpaRepo: WebhookOutboxJpaRepository
    @Autowired lateinit var txManager: PlatformTransactionManager
    @Autowired lateinit var entityManager: EntityManager

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
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun brlLine(accountId: AccountId, entryType: EntryType) = JournalLineRequest(
        accountId = accountId,
        entryType = entryType,
        monetaryEntry = FiatEntry(MonetaryAmount.of("500.00"), FiatCurrency.BRL, PaymentRail.PIX),
    )

    private fun buildStep(key: String, debit: AccountId = debitId, credit: AccountId = creditId) =
        WorkflowStepCommand(
            idempotencyKey = key,
            description = key,
            lines = listOf(brlLine(debit, EntryType.DEBIT), brlLine(credit, EntryType.CREDIT)),
        )

    private fun buildCmd(
        steps: List<WorkflowStepCommand>,
        policyRules: List<PolicyRule> = emptyList(),
        tenantId: TenantId = this.tenantId,
    ) = ExecuteWorkflowCommand(
        tenantId = tenantId,
        agentContext = agentCtx,
        steps = steps,
        policyRules = policyRules,
        createdBy = "integration-test",
    )

    private fun outboxCount(eventType: String): Long =
        (entityManager.createNativeQuery("SELECT COUNT(*) FROM webhook_outbox WHERE event_type = ?")
            .setParameter(1, eventType)
            .singleResult as Number).toLong()

    private fun auditCount(planId: WorkflowPlanId, status: String): Long =
        (entityManager.createNativeQuery(
            "SELECT COUNT(*) FROM agent_audit_events WHERE workflow_plan_id = ?::uuid AND status = ?"
        ).setParameter(1, planId.value.toString()).setParameter(2, status).singleResult as Number).toLong()

    // ── single step ───────────────────────────────────────────────────────────

    @Test
    fun `single step — posts transaction and plan reaches COMMITTED`() {
        val planId = executeService.execute(buildCmd(listOf(buildStep("step-0")))).getOrThrow()

        entityManager.flush()
        entityManager.clear()

        val plan = workflowPlanAdapter.findById(planId, tenantId)
        assertNotNull(plan)
        assertEquals(WorkflowStatus.COMMITTED, plan.status)
        assertNotNull(plan.completedAt)
        assertEquals(1, plan.steps.size)
        assertNotNull(plan.steps[0].transactionId)

        assertEquals(1L, outboxCount("workflow.committed"))
        assertEquals(1L, auditCount(planId, "PENDING"))
        assertEquals(1L, auditCount(planId, "COMPLETED"))
    }

    // ── multi-step ────────────────────────────────────────────────────────────

    @Test
    fun `multi-step — all steps committed atomically`() {
        val planId = executeService.execute(
            buildCmd(listOf(buildStep("ms-0"), buildStep("ms-1")))
        ).getOrThrow()

        entityManager.flush()
        entityManager.clear()

        val plan = workflowPlanAdapter.findById(planId, tenantId)
        assertNotNull(plan)
        assertEquals(WorkflowStatus.COMMITTED, plan.status)
        assertEquals(2, plan.steps.size)
        val txId0 = plan.steps[0].transactionId
        val txId1 = plan.steps[1].transactionId
        assertNotNull(txId0)
        assertNotNull(txId1)
        assertTrue(txId0 != txId1, "Each step must create a distinct transaction")

        assertEquals(1L, outboxCount("workflow.committed"))
    }

    // ── policy denial ─────────────────────────────────────────────────────────

    @Test
    fun `PolicyGuard denial — workflow never created`() {
        // MaxDebitPerSession(ZERO) rejects any debit amount > 0
        val rules = listOf(PolicyRule.MaxDebitPerSession(MonetaryAmount.of("0")))

        assertThrows<PolicyViolationException> {
            executeService.execute(buildCmd(listOf(buildStep("denied-0")), policyRules = rules))
        }
        // PolicyGuard throws before workflowPlanRepository.insert() — no rows expected
        entityManager.flush()
        val planCount = (entityManager
            .createNativeQuery("SELECT COUNT(*) FROM workflow_plans WHERE tenant_id = ?::uuid")
            .setParameter(1, tenantId.value.toString())
            .singleResult as Number).toLong()
        assertEquals(0L, planCount)
    }

    // ── partial failure ───────────────────────────────────────────────────────

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `partial failure — @Transactional rolls back all writes when a step fails`() {
        val txTemplate = TransactionTemplate(txManager)
        val localTenantId = TenantId.generate()
        val localDebit = AccountId.generate()
        val localCredit = AccountId.generate()

        // Accounts must be committed before the service's own transaction starts
        txTemplate.execute {
            accountAdapter.save(Account.create(localDebit, localTenantId, "d", FiatCurrency.BRL, AccountType.ASSET, now, "test"))
            accountAdapter.save(Account.create(localCredit, localTenantId, "c", FiatCurrency.BRL, AccountType.LIABILITY, now, "test"))
        }

        val unknownId = AccountId.generate() // never saved → TransactionAccountNotFound on step 1
        val cmd = buildCmd(
            steps = listOf(
                buildStep("pf-0", localDebit, localCredit),
                buildStep("pf-1", localDebit, unknownId),
            ),
            tenantId = localTenantId,
        )

        assertThrows<RuntimeException> { executeService.execute(cmd) }

        // Service transaction rolled back — no plan, no transactions for this tenant
        val planCount = txTemplate.execute {
            (entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM workflow_plans WHERE tenant_id = ?::uuid"
            ).setParameter(1, localTenantId.value.toString()).singleResult as Number).toLong()
        }!!
        assertEquals(0L, planCount)

        val txCount = txTemplate.execute {
            (entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM transactions WHERE tenant_id = ?::uuid"
            ).setParameter(1, localTenantId.value.toString()).singleResult as Number).toLong()
        }!!
        assertEquals(0L, txCount)

        // Cleanup accounts committed by setup (outside test transaction scope)
        txTemplate.execute {
            entityManager.createNativeQuery(
                "DELETE FROM accounts WHERE tenant_id = ?::uuid"
            ).setParameter(1, localTenantId.value.toString()).executeUpdate()
        }
    }

    // ── idempotency ───────────────────────────────────────────────────────────

    @Test
    fun `idempotency — same step key on second call returns same transaction ID`() {
        val planId1 = executeService.execute(buildCmd(listOf(buildStep("idem-k1")))).getOrThrow()
        val planId2 = executeService.execute(buildCmd(listOf(buildStep("idem-k1")))).getOrThrow()

        entityManager.flush()
        entityManager.clear()

        val txId1 = workflowPlanAdapter.findById(planId1, tenantId)!!.steps[0].transactionId!!
        val txId2 = workflowPlanAdapter.findById(planId2, tenantId)!!.steps[0].transactionId!!

        assertEquals(txId1, txId2, "Same idempotencyKey must yield the same transactionId")
        // Two distinct plans were created, but both reference the same underlying transaction
        assertTrue(planId1 != planId2, "Each execute() call produces a distinct plan")
    }
}
