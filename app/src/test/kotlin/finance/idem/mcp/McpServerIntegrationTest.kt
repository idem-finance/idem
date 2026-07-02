package finance.idem.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import finance.idem.TestcontainersConfiguration
import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.AgentAuditEvent
import finance.idem.core.agentic.AgentAuditStatus
import finance.idem.core.agentic.AgentContext
import finance.idem.core.agentic.PolicyRule
import finance.idem.core.agentic.PolicyViolationException
import finance.idem.core.ledger.Account
import finance.idem.core.ledger.AccountType
import finance.idem.core.ledger.EntryStatus
import finance.idem.core.ledger.Settlement
import finance.idem.core.security.ApiScope
import finance.idem.infrastructure.persistence.AccountRepositoryAdapter
import finance.idem.infrastructure.persistence.policy.PolicyRepositoryAdapter
import finance.idem.infrastructure.persistence.reconciliation.SettlementRepositoryAdapter
import finance.idem.infrastructure.security.ApiKeyAuthentication
import finance.idem.infrastructure.security.ApiKeyService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.execution.ToolExecutionException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.TestPropertySource
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

private const val HMAC_SECRET = "test-only-insecure-hmac-secret"

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "idem.audit.hmac-secret=$HMAC_SECRET",
        "idem.chain.alchemy-webhook-signing-key=unused-in-mcp-test",
    ],
)
class McpServerIntegrationTest {
    @Autowired lateinit var toolCallbackProvider: ToolCallbackProvider

    @Autowired lateinit var apiKeyService: ApiKeyService

    @Autowired lateinit var accountRepository: AccountRepositoryAdapter

    @Autowired lateinit var settlementRepository: SettlementRepositoryAdapter

    @Autowired lateinit var policyRepository: PolicyRepositoryAdapter

    @Autowired lateinit var dataSource: DataSource

    private val objectMapper = jacksonObjectMapper()

    private data class Fixture(
        val tenantId: TenantId,
        val debitAccountId: AccountId,
        val creditAccountId: AccountId,
        val agentRawKey: String,
        val agentScopes: Set<ApiScope>,
        val readRawKey: String,
        val readScopes: Set<ApiScope>,
    )

    private lateinit var fixture: Fixture

    @BeforeEach
    fun setUp() {
        val tenantId = TenantId.generate()
        val debitId = AccountId.generate()
        val creditId = AccountId.generate()
        val now = Instant.now()
        accountRepository.save(Account.create(debitId, tenantId, "Asset-Debit", FiatCurrency.BRL, AccountType.ASSET, now, "test"))
        accountRepository.save(Account.create(creditId, tenantId, "Liability-Credit", FiatCurrency.BRL, AccountType.LIABILITY, now, "test"))

        val agentScopes = setOf(ApiScope.AGENTS_EXECUTE, ApiScope.AGENTS_ROLLBACK, ApiScope.AGENTS_AUDIT_READ)
        val readScopes = setOf(ApiScope.TRANSACTIONS_READ)
        val (agentKey, _) = apiKeyService.generate(tenantId, agentScopes)
        val (readKey, _) = apiKeyService.generate(tenantId, readScopes)

        fixture = Fixture(tenantId, debitId, creditId, agentKey, agentScopes, readKey, readScopes)
        // Seed a permissive rule so PolicyGuard allows debits in all happy-path tests
        policyRepository.save(tenantId, null, PolicyRule.MaxDebitPerSession(MonetaryAmount.of("9999999")))
    }

    // ── 1. postTransaction valid ──────────────────────────────────────────────

    @Test
    fun `postTransaction valid returns COMMITTED workflowPlanId`() {
        withAuth(fixture.agentRawKey, fixture.agentScopes) {
            val result =
                callTool(
                    "postTransaction",
                    mapOf(
                        "entries" to brlEntries(fixture.debitAccountId, fixture.creditAccountId),
                        "idempotencyKey" to "mcp-it-post-001",
                        "intentDescription" to "integration test",
                        "agentId" to "agent-it",
                        "sessionId" to "session-it-001",
                    ),
                )

            assertThat(result["status"].textValue()).isEqualTo("COMMITTED")
            val workflowPlanId = result["workflowPlanId"].textValue()
            assertThat(workflowPlanId).isNotBlank()

            assertWorkflowStatus(UUID.fromString(workflowPlanId), "COMMITTED")
        }
    }

    // ── 2. postTransaction policy violation (blocked by #200) ─────────────────

    @Test
    fun `postTransaction policy violation throws PolicyViolationException`() {
        // Create a fresh tenant with no policy rules → default deny-all kicks in
        val denyTenantId = TenantId.generate()
        val denyDebitId = AccountId.generate()
        val denyCreditId = AccountId.generate()
        val now = Instant.now()
        accountRepository.save(Account.create(denyDebitId, denyTenantId, "Asset", FiatCurrency.BRL, AccountType.ASSET, now, "test"))
        accountRepository.save(
            Account.create(denyCreditId, denyTenantId, "Liability", FiatCurrency.BRL, AccountType.LIABILITY, now, "test"),
        )
        val (denyKey, _) = apiKeyService.generate(denyTenantId, setOf(ApiScope.AGENTS_EXECUTE))
        // No policy rules seeded for denyTenantId → MaxDebitPerSession(ZERO) default blocks all debits

        withAuth(denyTenantId, denyKey, setOf(ApiScope.AGENTS_EXECUTE)) {
            val ex =
                assertThrows<ToolExecutionException> {
                    callTool(
                        "postTransaction",
                        mapOf(
                            "entries" to brlEntries(denyDebitId, denyCreditId),
                            "idempotencyKey" to "denied-policy-001",
                            "agentId" to "agent-it",
                            "sessionId" to "session-denied-001",
                        ),
                    )
                }
            assertThat(ex.cause).isInstanceOf(PolicyViolationException::class.java)
        }
    }

    // ── 3. rollbackWorkflow valid ─────────────────────────────────────────────

    @Test
    fun `rollbackWorkflow creates compensating transactions and marks plan ROLLED_BACK`() {
        withAuth(fixture.agentRawKey, fixture.agentScopes) {
            val postResult =
                callTool(
                    "postTransaction",
                    mapOf(
                        "entries" to brlEntries(fixture.debitAccountId, fixture.creditAccountId),
                        "idempotencyKey" to "mcp-rb-001",
                        "intentDescription" to "to be rolled back",
                        "agentId" to "agent-rb",
                        "sessionId" to "session-rb-001",
                    ),
                )
            assertThat(postResult["status"].textValue()).isEqualTo("COMMITTED")
            val planId = postResult["workflowPlanId"].textValue()

            val rbResult =
                callTool(
                    "rollbackWorkflow",
                    mapOf(
                        "workflowPlanId" to planId,
                        "reason" to "integration test rollback",
                        "agentId" to "agent-rb",
                        "sessionId" to "session-rb-001",
                    ),
                )

            assertThat(rbResult["rollbackId"].textValue()).isEqualTo(planId)
            assertThat(rbResult["status"].textValue()).isEqualTo("ROLLED_BACK")
            val steps = rbResult["compensatedSteps"]
            assertThat(steps.isArray).isTrue()
            assertThat(steps.size()).isEqualTo(1)
            assertThat(steps[0]["compensatingTransactionId"].textValue()).isNotBlank()

            assertWorkflowStatus(UUID.fromString(planId), "ROLLED_BACK")
        }
    }

    // ── 4. reconcileBatch match found ─────────────────────────────────────────

    @Test
    fun `reconcileBatch match found returns matched count and settles entries`() {
        val wallet = "0x" + "ab12".repeat(10)
        val amount = "200.000000"
        val windowStart = Instant.now().minusSeconds(60)
        val confirmedAt = Instant.now()

        withAuth(fixture.agentRawKey, fixture.agentScopes) {
            // Post a FIAT transaction to obtain a real TransactionId for matchedTransactionId.
            // FIAT-only transactions skip BasicReconciliationService, so no auto-settle occurs.
            val seedTx =
                callTool(
                    "postTransaction",
                    mapOf(
                        "entries" to brlEntries(fixture.debitAccountId, fixture.creditAccountId),
                        "idempotencyKey" to "reconcile-seed-tx-001",
                        "agentId" to "agent-reconcile",
                        "sessionId" to "session-reconcile-seed",
                    ),
                )
            val seedTxId = fetchFirstTransactionId(seedTx["workflowPlanId"].textValue())

            // PENDING settlement — a journal line waiting for on-chain confirmation
            settlementRepository.save(
                Settlement(
                    id = UUID.randomUUID(),
                    tenantId = fixture.tenantId,
                    accountId = fixture.creditAccountId,
                    amount = MonetaryAmount.of(amount),
                    token = StablecoinToken.USDC,
                    chainId = ChainId.EVM,
                    walletAddress = wallet,
                    status = EntryStatus.PENDING,
                    expectedFromAddress = null,
                    createdAt = windowStart,
                    createdBy = "test",
                ),
            )

            // UNMATCHED settlement — a chain event needing batch reconciliation.
            // matchedTransactionId is always set in production (the on-chain tx ID);
            // WebhookOutboxEntry.transactionSettled() requiresNotNull on it.
            settlementRepository.save(
                Settlement(
                    id = UUID.randomUUID(),
                    tenantId = fixture.tenantId,
                    accountId = fixture.creditAccountId,
                    amount = MonetaryAmount.of(amount),
                    token = StablecoinToken.USDC,
                    chainId = ChainId.EVM,
                    walletAddress = wallet,
                    status = EntryStatus.UNMATCHED,
                    matchedTransactionId = seedTxId,
                    txHash = "0x" + "cd34".repeat(16),
                    blockNumber = 19_000_000L,
                    confirmedAt = confirmedAt,
                    expectedFromAddress = null,
                    createdAt = windowStart,
                    createdBy = "chain-reader",
                ),
            )

            val result =
                callTool(
                    "reconcileBatch",
                    mapOf(
                        "accountId" to fixture.creditAccountId.value.toString(),
                        "from" to windowStart.toString(),
                        "to" to confirmedAt.plusSeconds(60).toString(),
                    ),
                )

            assertThat(result["matched"].intValue()).isEqualTo(1)
            assertThat(result["unmatched"].intValue()).isEqualTo(0)
            assertThat(result["exceptions"].size()).isEqualTo(0)
        }
    }

    // ── 5. reconcileBatch no match ────────────────────────────────────────────

    @Test
    fun `reconcileBatch empty window returns zero matched and unmatched`() {
        val futureStart = Instant.now().plusSeconds(3600)
        val futureEnd = futureStart.plusSeconds(3600)

        withAuth(fixture.agentRawKey, fixture.agentScopes) {
            val result =
                callTool(
                    "reconcileBatch",
                    mapOf(
                        "from" to futureStart.toString(),
                        "to" to futureEnd.toString(),
                    ),
                )

            assertThat(result["matched"].intValue()).isEqualTo(0)
            assertThat(result["unmatched"].intValue()).isEqualTo(0)
            assertThat(result["exceptions"].isArray).isTrue()
            assertThat(result["exceptions"].size()).isEqualTo(0)
        }
    }

    // ── 6. getAgentAuditLog — HMAC integrity ─────────────────────────────────

    @Test
    fun `getAgentAuditLog returns events whose HMAC signatures pass re-verification`() {
        val sessionId = "session-hmac-${UUID.randomUUID()}"

        withAuth(fixture.agentRawKey, fixture.agentScopes) {
            callTool(
                "postTransaction",
                mapOf(
                    "entries" to brlEntries(fixture.debitAccountId, fixture.creditAccountId),
                    "idempotencyKey" to "mcp-hmac-001",
                    "intentDescription" to "hmac-test intent",
                    "agentId" to "agent-hmac",
                    "sessionId" to sessionId,
                ),
            )

            val auditResult =
                callTool(
                    "getAgentAuditLog",
                    mapOf("sessionId" to sessionId, "limit" to 50),
                )

            val events = auditResult["auditEvents"]
            assertThat(events.isArray).isTrue()
            // ExecuteWorkflowService writes one PENDING + one COMPLETED audit event
            assertThat(events.size()).isGreaterThanOrEqualTo(2)
            events.forEach { event ->
                assertThat(event["hmacSignature"].textValue()).isNotBlank()
            }

            // Re-verify every stored HMAC by reconstructing the domain event from DB rows
            verifyAuditHmacs(sessionId)
        }
    }

    // ── 7. missing AGENTS_EXECUTE scope → AccessDeniedException ──────────────

    @Test
    fun `postTransaction with read-only key is rejected by @PreAuthorize`() {
        withAuth(fixture.readRawKey, fixture.readScopes) {
            // Spring AI's MethodToolCallback invokes tools via Method.invoke() (reflection).
            // Any exception thrown by the AOP proxy (including AccessDeniedException from
            // @PreAuthorize) is wrapped by Java in InvocationTargetException, which
            // MethodToolCallback then re-wraps as ToolExecutionException.
            val ex =
                assertThrows<ToolExecutionException> {
                    callTool(
                        "postTransaction",
                        mapOf(
                            "entries" to brlEntries(fixture.debitAccountId, fixture.creditAccountId),
                            "idempotencyKey" to "denied-scope-001",
                            "agentId" to "agent-it",
                            "sessionId" to "session-denied-002",
                        ),
                    )
                }
            assertThat(ex.cause).isInstanceOf(AccessDeniedException::class.java)
        }
    }

    // ── 8. Demo scenario: execute → reconcile → rollback ─────────────────────

    @Test
    fun `demo scenario - execute workflow, reconcile batch, rollback, verify full audit trail`() {
        val sessionId = "session-demo-${UUID.randomUUID()}"
        val wallet = "0x" + "ef56".repeat(10)
        val settlementAmount = "300.000000"
        val windowStart = Instant.now().minusSeconds(60)
        val confirmedAt = Instant.now()

        withAuth(fixture.agentRawKey, fixture.agentScopes) {
            // Step 1: execute the workflow
            val postResult =
                callTool(
                    "postTransaction",
                    mapOf(
                        "entries" to brlEntries(fixture.debitAccountId, fixture.creditAccountId),
                        "idempotencyKey" to "demo-exec-001",
                        "intentDescription" to "demo cross-border transfer",
                        "agentId" to "agent-demo",
                        "sessionId" to sessionId,
                    ),
                )
            assertThat(postResult["status"].textValue()).isEqualTo("COMMITTED")
            val planId = postResult["workflowPlanId"].textValue()
            val seedTxId = fetchFirstTransactionId(planId)

            // Step 2: seed the reconciliation fixtures (simulates chain reader + journal line)
            settlementRepository.save(
                Settlement(
                    id = UUID.randomUUID(),
                    tenantId = fixture.tenantId,
                    accountId = fixture.creditAccountId,
                    amount = MonetaryAmount.of(settlementAmount),
                    token = StablecoinToken.USDC,
                    chainId = ChainId.EVM,
                    walletAddress = wallet,
                    status = EntryStatus.PENDING,
                    expectedFromAddress = null,
                    createdAt = windowStart,
                    createdBy = "test",
                ),
            )
            settlementRepository.save(
                Settlement(
                    id = UUID.randomUUID(),
                    tenantId = fixture.tenantId,
                    accountId = fixture.creditAccountId,
                    amount = MonetaryAmount.of(settlementAmount),
                    token = StablecoinToken.USDC,
                    chainId = ChainId.EVM,
                    walletAddress = wallet,
                    status = EntryStatus.UNMATCHED,
                    matchedTransactionId = seedTxId,
                    txHash = "0x" + "fe78".repeat(16),
                    blockNumber = 20_000_000L,
                    confirmedAt = confirmedAt,
                    expectedFromAddress = null,
                    createdAt = windowStart,
                    createdBy = "chain-reader",
                ),
            )

            // Step 3: reconcile — the UNMATCHED chain event matches the PENDING settlement
            val reconcileResult =
                callTool(
                    "reconcileBatch",
                    mapOf(
                        "accountId" to fixture.creditAccountId.value.toString(),
                        "from" to windowStart.toString(),
                        "to" to confirmedAt.plusSeconds(60).toString(),
                    ),
                )
            assertThat(reconcileResult["matched"].intValue()).isEqualTo(1)

            // Step 4: rollback the workflow via saga compensation
            val rbResult =
                callTool(
                    "rollbackWorkflow",
                    mapOf(
                        "workflowPlanId" to planId,
                        "reason" to "demo: reverting for compliance review",
                        "agentId" to "agent-demo",
                        "sessionId" to sessionId,
                    ),
                )
            assertThat(rbResult["status"].textValue()).isEqualTo("ROLLED_BACK")

            // Step 5: audit log must contain events for both execute and rollback
            val auditResult =
                callTool(
                    "getAgentAuditLog",
                    mapOf("sessionId" to sessionId, "limit" to 50),
                )
            val events = auditResult["auditEvents"]
            // execute: PENDING + COMPLETED; rollback: PENDING + COMPLETED = 4 events minimum
            assertThat(events.size()).isGreaterThanOrEqualTo(4)
            val statuses = events.map { it["status"].textValue() }.toSet()
            assertThat(statuses).containsAll(listOf("PENDING", "COMPLETED"))

            // Step 6: plan must be in terminal ROLLED_BACK state
            assertWorkflowStatus(UUID.fromString(planId), "ROLLED_BACK")

            // Step 7: all audit events must pass HMAC integrity check
            verifyAuditHmacs(sessionId)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun brlEntries(
        debitId: AccountId,
        creditId: AccountId,
    ) = listOf(
        mapOf(
            "accountId" to debitId.value.toString(),
            "entryType" to "DEBIT",
            "monetaryEntryType" to "FIAT",
            "amount" to "100.00",
            "currency" to "BRL",
            "rail" to "PIX",
        ),
        mapOf(
            "accountId" to creditId.value.toString(),
            "entryType" to "CREDIT",
            "monetaryEntryType" to "FIAT",
            "amount" to "100.00",
            "currency" to "BRL",
            "rail" to "PIX",
        ),
    )

    // Sets a real ApiKeyAuthentication on the calling thread and clears it in finally.
    // Spring AI's MethodToolCallbackProvider invokes @Tool methods synchronously on the
    // same thread, so @PreAuthorize and tenantId() both see this SecurityContext.
    private fun withAuth(
        rawKey: String,
        scopes: Set<ApiScope>,
        block: () -> Unit,
    ) = withAuth(fixture.tenantId, rawKey, scopes, block)

    private fun withAuth(
        tenantId: TenantId,
        rawKey: String,
        scopes: Set<ApiScope>,
        block: () -> Unit,
    ) {
        val auth =
            ApiKeyAuthentication(
                tenantId = tenantId,
                keyPrefix = rawKey.take(12),
                authorities = scopes.map { SimpleGrantedAuthority(it.name) }.toSet(),
            )
        SecurityContextHolder.getContext().authentication = auth
        try {
            block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    // Invokes a tool via Spring AI's MethodToolCallbackProvider.
    // Because IdemMcpServer is a Spring-managed AOP proxy, the call goes through
    // @PreAuthorize before reaching the method body — same path as the SSE transport.
    private fun callTool(
        name: String,
        args: Map<String, Any?>,
    ): JsonNode {
        val cb = toolCallbackProvider.toolCallbacks.first { it.toolDefinition.name() == name }
        val filteredArgs = args.filterValues { it != null }
        return objectMapper.readTree(cb.call(objectMapper.writeValueAsString(filteredArgs)))
    }

    private fun fetchFirstTransactionId(planId: String): TransactionId =
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${fixture.tenantId.value}'")
            val txId =
                conn
                    .prepareStatement(
                        "SELECT transaction_id FROM workflow_steps WHERE workflow_plan_id = ?::uuid LIMIT 1",
                    ).use { ps ->
                        ps.setString(1, planId)
                        ps.executeQuery().use { rs ->
                            check(rs.next()) { "No workflow_steps row found for plan $planId" }
                            rs.getString("transaction_id")
                        }
                    }
            conn.commit()
            TransactionId.of(txId)
        }

    private fun assertWorkflowStatus(
        planId: UUID,
        expectedStatus: String,
    ) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.createStatement().execute("SET LOCAL app.tenant_id = '${fixture.tenantId.value}'")
            conn
                .prepareStatement(
                    "SELECT status FROM workflow_plans WHERE id = ?::uuid AND tenant_id = ?::uuid",
                ).use { ps ->
                    ps.setString(1, planId.toString())
                    ps.setString(2, fixture.tenantId.value.toString())
                    ps.executeQuery().use { rs ->
                        assertThat(rs.next())
                            .withFailMessage("No workflow_plan row found for id=$planId")
                            .isTrue()
                        assertThat(rs.getString("status")).isEqualTo(expectedStatus)
                    }
                }
            conn.commit()
        }
    }

    private fun verifyAuditHmacs(sessionId: String) {
        data class AuditRow(
            val id: UUID,
            val workflowPlanId: UUID,
            val agentId: String,
            val agentSessionId: String,
            val intent: String?,
            val status: String,
            val outcome: String?,
            val occurredAt: Instant,
            val storedHmac: String,
        )

        val rows: List<AuditRow> =
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                conn.createStatement().execute("SET LOCAL app.tenant_id = '${fixture.tenantId.value}'")
                val result =
                    conn
                        .prepareStatement(
                            """SELECT id, workflow_plan_id, agent_id, session_id, intent,
                          status, outcome, occurred_at, hmac
                   FROM agent_audit_events
                   WHERE session_id = ?
                   ORDER BY occurred_at""",
                        ).use { ps ->
                            ps.setString(1, sessionId)
                            ps.executeQuery().use { rs ->
                                val acc = mutableListOf<AuditRow>()
                                while (rs.next()) {
                                    acc.add(
                                        AuditRow(
                                            id = rs.getObject("id", UUID::class.java),
                                            workflowPlanId = rs.getObject("workflow_plan_id", UUID::class.java),
                                            agentId = rs.getString("agent_id"),
                                            agentSessionId = rs.getString("session_id"),
                                            intent = rs.getString("intent"),
                                            status = rs.getString("status"),
                                            outcome = rs.getString("outcome"),
                                            occurredAt = rs.getTimestamp("occurred_at").toInstant(),
                                            storedHmac = rs.getString("hmac"),
                                        ),
                                    )
                                }
                                acc.toList()
                            }
                        }
                conn.commit()
                result
            }

        assertThat(rows.size).isGreaterThan(0)
        rows.forEach { row ->
            val event =
                AgentAuditEvent(
                    id = row.id,
                    workflowPlanId = WorkflowPlanId(row.workflowPlanId),
                    tenantId = fixture.tenantId,
                    agentContext = AgentContext(agentId = row.agentId, sessionId = row.agentSessionId),
                    status = AgentAuditStatus.valueOf(row.status),
                    intent = row.intent,
                    outcome = row.outcome,
                    occurredAt = row.occurredAt,
                )
            val recomputed = event.computeHmac(HMAC_SECRET)
            assertThat(recomputed)
                .withFailMessage("HMAC mismatch for audit event id=${row.id} status=${row.status}")
                .isEqualTo(row.storedHmac)
        }
    }
}
