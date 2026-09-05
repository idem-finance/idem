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
import finance.idem.infrastructure.compliance.ComplianceConfig
import finance.idem.infrastructure.compliance.ComplianceQueueRepositoryAdapter
import finance.idem.infrastructure.persistence.AccountRepositoryAdapter
import finance.idem.infrastructure.persistence.PersistenceTestConfig
import finance.idem.infrastructure.persistence.TransactionRepositoryAdapter
import finance.idem.infrastructure.persistence.audit.AgentAuditRepositoryAdapter
import finance.idem.infrastructure.persistence.audit.AuditConfig
import finance.idem.infrastructure.persistence.audit.AuditRepositoryAdapter
import finance.idem.infrastructure.persistence.events.DomainEventRepositoryAdapter
import finance.idem.infrastructure.persistence.idempotency.PostgresIdempotencyStore
import finance.idem.infrastructure.persistence.outbox.WebhookOutboxRepositoryAdapter
import finance.idem.infrastructure.persistence.policy.PolicyRepositoryAdapter
import finance.idem.infrastructure.persistence.policy.PolicyRuleDataModel
import finance.idem.infrastructure.persistence.policy.PolicyRuleJpaRepository
import finance.idem.infrastructure.persistence.policy.SessionDebitAdapter
import finance.idem.infrastructure.persistence.reconciliation.SettlementRepositoryAdapter
import finance.idem.infrastructure.persistence.workflow.WorkflowPlanRepositoryAdapter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    ExecuteWorkflowService::class,
    AgentAuditRecorder::class,
    PostTransactionService::class,
    BasicReconciliationService::class,
    WorkflowPlanRepositoryAdapter::class,
    AgentAuditRepositoryAdapter::class,
    AuditConfig::class,
    AuditRepositoryAdapter::class,
    WebhookOutboxRepositoryAdapter::class,
    DomainEventRepositoryAdapter::class,
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
class ExecuteWorkflowServiceIntegrationTest : PostgresServiceIntegrationTestBase() {
    @Autowired lateinit var executeService: ExecuteWorkflowService

    @Autowired lateinit var accountAdapter: AccountRepositoryAdapter

    @Autowired lateinit var workflowPlanAdapter: WorkflowPlanRepositoryAdapter

    @Autowired lateinit var transactionAdapter: TransactionRepositoryAdapter

    @Autowired lateinit var txManager: PlatformTransactionManager

    @Autowired lateinit var policyRepository: PolicyRepositoryAdapter

    @Autowired lateinit var agentAuditAdapter: AgentAuditRepositoryAdapter

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
        // Seed a permissive rule so PolicyGuard allows debits in all tests
        policyRepository.save(tenantId, null, PolicyRule.MaxDebitPerSession(MonetaryAmount.of("99999")))
    }

    @AfterEach
    fun clearTraceContext() {
        MDC.remove(finance.idem.infrastructure.observability.TraceIdFilter.MDC_KEY)
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

    private fun buildCmd(
        steps: List<WorkflowStepCommand>,
        tenantId: TenantId = this.tenantId,
    ) = ExecuteWorkflowCommand(
        tenantId = tenantId,
        agentContext = agentCtx,
        steps = steps,
        createdBy = "integration-test",
    )

    private fun auditCount(
        planId: WorkflowPlanId,
        status: String,
    ): Long =
        (
            entityManager
                .createNativeQuery(
                    "SELECT COUNT(*) FROM agent_audit_events WHERE workflow_plan_id = ?::uuid AND status = ?",
                ).setParameter(1, planId.value.toString())
                .setParameter(2, status)
                .singleResult as Number
        ).toLong()

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
        assertEquals(1L, domainEventCount("WORKFLOW_COMMITTED"))
        assertEquals(1L, domainEventCount("TRANSACTION_COMMITTED"))
    }

    // ── multi-step ────────────────────────────────────────────────────────────

    @Test
    fun `multi-step — all steps committed atomically`() {
        val planId =
            executeService
                .execute(
                    buildCmd(listOf(buildStep("ms-0"), buildStep("ms-1"))),
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
        assertEquals(1L, auditCount(planId, "PENDING"))
        assertEquals(1L, auditCount(planId, "COMPLETED"))
        assertEquals(1L, domainEventCount("WORKFLOW_COMMITTED"))
        assertEquals(2L, domainEventCount("TRANSACTION_COMMITTED"))
    }

    @Test
    fun `all domain_events rows from one execute() call share one correlation_id`() {
        MDC.put(finance.idem.infrastructure.observability.TraceIdFilter.MDC_KEY, "test-trace-shared")

        executeService.execute(buildCmd(listOf(buildStep("corr-0"), buildStep("corr-1")))).getOrThrow()

        entityManager.flush()
        entityManager.clear()

        val correlationIds =
            entityManager
                .createNativeQuery("SELECT DISTINCT correlation_id FROM domain_events WHERE tenant_id = ?::uuid")
                .setParameter(1, tenantId.value.toString())
                .resultList
        assertEquals(listOf("test-trace-shared"), correlationIds, "every event from one request must share its trace id")
    }

    // ── policy denial ─────────────────────────────────────────────────────────

    @Test
    fun `PolicyGuard denial — workflow never created`() {
        // Add a deny-all rule (overrides the permissive rule from setup)
        policyRepository.save(tenantId, null, PolicyRule.MaxDebitPerSession(MonetaryAmount.of("0")))

        assertThrows<PolicyViolationException> {
            executeService.execute(buildCmd(listOf(buildStep("denied-0"))))
        }
        // PolicyGuard throws before workflowPlanRepository.insert() — no rows expected
        val planCount =
            (
                entityManager
                    .createNativeQuery("SELECT COUNT(*) FROM workflow_plans WHERE tenant_id = ?::uuid")
                    .setParameter(1, tenantId.value.toString())
                    .singleResult as Number
            ).toLong()
        assertEquals(0L, planCount)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `PolicyGuard denial — durable FAILED audit committed despite business rollback`() {
        val txTemplate = TransactionTemplate(txManager)
        val localTenant = TenantId.generate()
        // Commit a deny-all rule before the service's own transaction starts.
        txTemplate.execute {
            policyRepository.save(localTenant, null, PolicyRule.MaxDebitPerSession(MonetaryAmount.of("0")))
        }

        assertThrows<PolicyViolationException> {
            executeService.execute(buildCmd(listOf(buildStep("denied-durable")), tenantId = localTenant))
        }

        // No plan row (the business transaction rolled back)…
        val planCount =
            txTemplate.execute {
                (
                    entityManager
                        .createNativeQuery("SELECT COUNT(*) FROM workflow_plans WHERE tenant_id = ?::uuid")
                        .setParameter(1, localTenant.value.toString())
                        .singleResult as Number
                ).toLong()
            }!!
        assertEquals(0L, planCount)

        // …but the denied attempt is durably recorded as a FAILED audit event.
        val events = txTemplate.execute { agentAuditAdapter.findByFilter(localTenant) }!!
        val denied = events.filter { it.status == "FAILED" }
        assertEquals(1, denied.size, "Denied agent attempt must leave exactly one FAILED audit event")

        // …and an AGENT_ACTION_FLAGGED domain event, written in the same REQUIRES_NEW
        // transaction as the FAILED audit event via AgentAuditRecorder — surviving the same
        // business-transaction rollback.
        val flaggedCount =
            txTemplate.execute {
                (
                    entityManager
                        .createNativeQuery(
                            "SELECT COUNT(*) FROM domain_events WHERE tenant_id = ?::uuid AND event_type = 'AGENT_ACTION_FLAGGED'",
                        ).setParameter(1, localTenant.value.toString())
                        .singleResult as Number
                ).toLong()
            }!!
        assertEquals(1L, flaggedCount, "Denied agent attempt must leave exactly one AGENT_ACTION_FLAGGED domain event")
    }

    // ── partial failure ───────────────────────────────────────────────────────

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `partial failure — @Transactional rolls back all writes when a step fails`() {
        val txTemplate = TransactionTemplate(txManager)
        val localTenantId = TenantId.generate()
        val localDebit = AccountId.generate()
        val localCredit = AccountId.generate()

        // Accounts and a permissive policy rule must be committed before the service's own transaction starts
        txTemplate.execute {
            accountAdapter.save(Account.create(localDebit, localTenantId, "d", FiatCurrency.BRL, AccountType.ASSET, now, "test"))
            accountAdapter.save(Account.create(localCredit, localTenantId, "c", FiatCurrency.BRL, AccountType.LIABILITY, now, "test"))
            policyRepository.save(localTenantId, null, PolicyRule.MaxDebitPerSession(MonetaryAmount.of("99999")))
        }

        val unknownId = AccountId.generate() // never saved → TransactionAccountNotFound on step 1
        val cmd =
            buildCmd(
                steps =
                    listOf(
                        buildStep("pf-0", localDebit, localCredit),
                        buildStep("pf-1", localDebit, unknownId),
                    ),
                tenantId = localTenantId,
            )

        assertThrows<RuntimeException> { executeService.execute(cmd) }

        // Service transaction rolled back — no plan, no transactions for this tenant
        val planCount =
            txTemplate.execute {
                (
                    entityManager
                        .createNativeQuery(
                            "SELECT COUNT(*) FROM workflow_plans WHERE tenant_id = ?::uuid",
                        ).setParameter(1, localTenantId.value.toString())
                        .singleResult as Number
                ).toLong()
            }!!
        assertEquals(0L, planCount)

        val txCount =
            txTemplate.execute {
                (
                    entityManager
                        .createNativeQuery(
                            "SELECT COUNT(*) FROM transactions WHERE tenant_id = ?::uuid",
                        ).setParameter(1, localTenantId.value.toString())
                        .singleResult as Number
                ).toLong()
            }!!
        assertEquals(0L, txCount)

        val committedDomainEventCount =
            txTemplate.execute {
                (
                    entityManager
                        .createNativeQuery(
                            "SELECT COUNT(*) FROM domain_events WHERE tenant_id = ?::uuid " +
                                "AND event_type IN ('WORKFLOW_COMMITTED', 'TRANSACTION_COMMITTED')",
                        ).setParameter(1, localTenantId.value.toString())
                        .singleResult as Number
                ).toLong()
            }!!
        assertEquals(0L, committedDomainEventCount, "domain_events must roll back along with the plan/transaction rows")

        // Audit trail survives the rollback: the attempt (PENDING) and its failure (FAILED)
        // were written durably, even though the plan and transactions were rolled back.
        val events = txTemplate.execute { agentAuditAdapter.findByFilter(localTenantId) }!!
        assertTrue(events.any { it.status == "PENDING" }, "Durable PENDING audit must survive rollback")
        assertTrue(events.any { it.status == "FAILED" }, "Durable FAILED audit must survive rollback")

        // Cleanup: localTenantId accounts from setup tx AND this.tenantId accounts from @BeforeEach
        txTemplate.execute {
            entityManager
                .createNativeQuery(
                    "DELETE FROM accounts WHERE tenant_id = ?::uuid OR tenant_id = ?::uuid",
                ).setParameter(1, localTenantId.value.toString())
                .setParameter(2, tenantId.value.toString())
                .executeUpdate()
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
        assertTrue(planId1 != planId2, "Each execute() call produces a distinct plan")

        // Each plan execution writes its own audit pair and outbox entry
        assertEquals(1L, auditCount(planId1, "PENDING"))
        assertEquals(1L, auditCount(planId1, "COMPLETED"))
        assertEquals(1L, auditCount(planId2, "PENDING"))
        assertEquals(1L, auditCount(planId2, "COMPLETED"))
        assertEquals(2L, outboxCount("workflow.committed"))
        assertEquals(2L, domainEventCount("WORKFLOW_COMMITTED"))
    }
}
