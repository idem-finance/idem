package finance.idem.mcp

import finance.idem.application.agentic.CompensatedStepSummary
import finance.idem.application.agentic.ExecuteWorkflowCommand
import finance.idem.application.agentic.ExecuteWorkflowUseCase
import finance.idem.application.agentic.RollbackWorkflowCommand
import finance.idem.application.agentic.RollbackWorkflowSummary
import finance.idem.application.agentic.RollbackWorkflowUseCase
import finance.idem.application.ledger.AccountDescription
import finance.idem.application.ledger.Balance
import finance.idem.application.ledger.DescribeAccountQuery
import finance.idem.application.ledger.DescribeAccountUseCase
import finance.idem.application.ledger.EntryPage
import finance.idem.application.ledger.GetBalanceQuery
import finance.idem.application.ledger.GetBalanceUseCase
import finance.idem.application.ledger.GetEntriesQuery
import finance.idem.application.ledger.GetEntriesUseCase
import finance.idem.application.agentic.GetAgentAuditLogQuery
import finance.idem.application.agentic.GetAgentAuditLogUseCase
import finance.idem.application.port.AgentAuditView
import finance.idem.application.reconciliation.ReconcileEntriesCommand
import finance.idem.application.reconciliation.ReconcileEntriesResult
import finance.idem.application.reconciliation.ReconcileEntriesUseCase
import finance.idem.application.reconciliation.ReconciliationException
import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.TransactionId
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.PolicyViolationException
import java.math.BigDecimal
import finance.idem.core.ledger.JournalLine
import finance.idem.core.monetary.FiatEntry
import finance.idem.core.monetary.OnChainEntry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@ExtendWith(MockitoExtension::class)
class IdemMcpServerTest {

    @Mock lateinit var executeWorkflowUseCase: ExecuteWorkflowUseCase
    @Mock lateinit var getBalanceUseCase: GetBalanceUseCase
    @Mock lateinit var getEntriesUseCase: GetEntriesUseCase
    @Mock lateinit var describeAccountUseCase: DescribeAccountUseCase
    @Mock lateinit var rollbackWorkflowUseCase: RollbackWorkflowUseCase
    @Mock lateinit var reconcileEntriesUseCase: ReconcileEntriesUseCase
    @Mock lateinit var getAgentAuditLogUseCase: GetAgentAuditLogUseCase

    private lateinit var server: IdemMcpServer

    private val tenantId = TenantId.generate()
    private val accountId = AccountId.generate()

    @BeforeEach
    fun setUp() {
        server = IdemMcpServer(
            executeWorkflowUseCase, getBalanceUseCase, getEntriesUseCase, describeAccountUseCase,
            rollbackWorkflowUseCase, reconcileEntriesUseCase, getAgentAuditLogUseCase,
        )
        val auth = TestingAuthenticationToken(tenantId, null, "AGENTS_EXECUTE", "AGENTS_AUDIT_READ")
        SecurityContextHolder.getContext().authentication = auth
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── postTransaction ───────────────────────────────────────────────────────

    @Test
    fun `postTransaction builds single-step ExecuteWorkflowCommand and returns COMMITTED`() {
        val planId = WorkflowPlanId.generate()
        whenever(executeWorkflowUseCase.execute(any())).thenReturn(Result.success(planId))

        val entry = McpJournalLineInput(
            accountId = accountId.value.toString(),
            entryType = "DEBIT",
            monetaryEntryType = "FIAT",
            amount = "100.00",
            currency = "USD",
            rail = "WIRE",
        )
        val result = server.postTransaction(
            entries = listOf(entry),
            idempotencyKey = "idem-key-1",
            intentDescription = "Test transfer",
            agentId = "agent-1",
            sessionId = "session-1",
        )

        assertEquals(planId.value.toString(), result.workflowPlanId)
        assertEquals("COMMITTED", result.status)

        val captor = argumentCaptor<ExecuteWorkflowCommand>()
        verify(executeWorkflowUseCase).execute(captor.capture())
        val cmd = captor.firstValue
        assertEquals(tenantId, cmd.tenantId)
        assertEquals("agent-1", cmd.agentContext.agentId)
        assertEquals("session-1", cmd.agentContext.sessionId)
        assertEquals("Test transfer", cmd.agentContext.intent)
        assertEquals(1, cmd.steps.size)
        assertEquals("idem-key-1", cmd.steps[0].idempotencyKey)
        assertEquals(1, cmd.steps[0].lines.size)
        assertEquals(EntryType.DEBIT, cmd.steps[0].lines[0].entryType)
    }

    @Test
    fun `postTransaction populates apiKeyPrefix in AgentContext from authentication name`() {
        val planId = WorkflowPlanId.generate()
        whenever(executeWorkflowUseCase.execute(any())).thenReturn(Result.success(planId))

        val entry = McpJournalLineInput(
            accountId = accountId.value.toString(),
            entryType = "DEBIT",
            monetaryEntryType = "FIAT",
            amount = "50.00",
            currency = "USD",
            rail = "WIRE",
        )
        server.postTransaction(listOf(entry), "key", null, "agent", "session")

        val captor = argumentCaptor<ExecuteWorkflowCommand>()
        verify(executeWorkflowUseCase).execute(captor.capture())
        // apiKeyPrefix must be non-null — in production it is the verified 12-char key prefix
        // extracted from ApiKeyAuthentication.getName(); in tests it is the auth token's name
        assertNotNull(captor.firstValue.agentContext.apiKeyPrefix)
    }

    @Test
    fun `postTransaction propagates PolicyViolationException`() {
        whenever(executeWorkflowUseCase.execute(any())).thenReturn(
            Result.failure(PolicyViolationException(emptyList())),
        )
        val entry = McpJournalLineInput(
            accountId = accountId.value.toString(),
            entryType = "DEBIT",
            monetaryEntryType = "FIAT",
            amount = "100.00",
            currency = "USD",
            rail = "WIRE",
        )
        assertFailsWith<PolicyViolationException> {
            server.postTransaction(listOf(entry), "key", null, "agent", "session")
        }
    }

    // ── getBalance ────────────────────────────────────────────────────────────

    @Test
    fun `getBalance passes correct query and returns balance fields`() {
        val now = Instant.now()
        val balance = Balance(
            accountId = accountId,
            currency = FiatCurrency.USD,
            amount = MonetaryAmount.of("500.00"),
            normalBalance = EntryType.DEBIT,
            computedAt = now,
        )
        whenever(getBalanceUseCase.execute(any())).thenReturn(Result.success(balance))

        val result = server.getBalance(accountId = accountId.value.toString(), asOf = null)

        assertEquals(accountId.value.toString(), result.accountId)
        assertEquals("USD", result.currency)
        assertEquals("500.00", result.amount)

        val captor = argumentCaptor<GetBalanceQuery>()
        verify(getBalanceUseCase).execute(captor.capture())
        assertEquals(accountId, captor.firstValue.accountId)
        assertEquals(tenantId, captor.firstValue.tenantId)
    }

    @Test
    fun `getBalance parses asOf instant correctly`() {
        val asOf = Instant.parse("2025-01-15T10:00:00Z")
        val balance = Balance(accountId, FiatCurrency.USD, MonetaryAmount.of("0"), EntryType.DEBIT, Instant.now())
        whenever(getBalanceUseCase.execute(any())).thenReturn(Result.success(balance))

        server.getBalance(accountId = accountId.value.toString(), asOf = "2025-01-15T10:00:00Z")

        val captor = argumentCaptor<GetBalanceQuery>()
        verify(getBalanceUseCase).execute(captor.capture())
        assertEquals(asOf, captor.firstValue.asOf)
    }

    @Test
    fun `getBalance throws IllegalArgumentException for malformed asOf — not raw DateTimeParseException`() {
        assertFailsWith<IllegalArgumentException> {
            server.getBalance(accountId = accountId.value.toString(), asOf = "not-a-valid-date")
        }
    }

    // ── listEntries ───────────────────────────────────────────────────────────

    @Test
    fun `listEntries passes correct query and maps entries`() {
        val page = EntryPage(accountId = accountId, entries = emptyList(), nextCursor = null)
        whenever(getEntriesUseCase.execute(any())).thenReturn(Result.success(page))

        val result = server.listEntries(
            accountId = accountId.value.toString(),
            from = null,
            to = null,
            limit = 25,
            cursor = null,
        )

        assertEquals(accountId.value.toString(), result.accountId)
        assertEquals(0, result.entries.size)

        val captor = argumentCaptor<GetEntriesQuery>()
        verify(getEntriesUseCase).execute(captor.capture())
        assertEquals(accountId, captor.firstValue.accountId)
        assertEquals(tenantId, captor.firstValue.tenantId)
        assertEquals(25, captor.firstValue.limit)
    }

    @Test
    fun `listEntries throws IllegalArgumentException for malformed from — not raw DateTimeParseException`() {
        assertFailsWith<IllegalArgumentException> {
            server.listEntries(accountId.value.toString(), from = "not-a-date", to = null, limit = null, cursor = null)
        }
    }

    @Test
    fun `listEntries throws IllegalArgumentException for malformed to`() {
        assertFailsWith<IllegalArgumentException> {
            server.listEntries(accountId.value.toString(), from = null, to = "not-a-date", limit = null, cursor = null)
        }
    }

    @Test
    fun `listEntries clamps limit to 200`() {
        val page = EntryPage(accountId = accountId, entries = emptyList(), nextCursor = null)
        whenever(getEntriesUseCase.execute(any())).thenReturn(Result.success(page))

        server.listEntries(accountId.value.toString(), null, null, 999, null)

        val captor = argumentCaptor<GetEntriesQuery>()
        verify(getEntriesUseCase).execute(captor.capture())
        assertEquals(200, captor.firstValue.limit)
    }

    @Test
    fun `listEntries maps JournalLine to EntryItem correctly`() {
        val txId = TransactionId(UUID.randomUUID())
        val lineId = UUID.randomUUID()
        val now = Instant.now()
        val line = JournalLine(
            id = lineId,
            transactionId = txId,
            accountId = accountId,
            tenantId = tenantId,
            entryType = EntryType.CREDIT,
            monetaryEntry = FiatEntry(MonetaryAmount.of("250"), FiatCurrency.BRL, PaymentRail.PIX),
            description = "Test payment",
            createdAt = now,
            createdBy = "agent-1",
        )
        val page = EntryPage(accountId = accountId, entries = listOf(line), nextCursor = "cursor-abc")
        whenever(getEntriesUseCase.execute(any())).thenReturn(Result.success(page))

        val result = server.listEntries(accountId.value.toString(), null, null, null, null)

        assertEquals(1, result.entries.size)
        assertEquals(lineId.toString(), result.entries[0].id)
        assertEquals(txId.value.toString(), result.entries[0].transactionId)
        assertEquals("CREDIT", result.entries[0].entryType)
        assertEquals("250", result.entries[0].amount)
        assertEquals("BRL", result.entries[0].currency)
        assertEquals("Test payment", result.entries[0].description)
        assertEquals("cursor-abc", result.nextCursor)
    }

    // ── McpJournalLineInput ───────────────────────────────────────────────────

    @Test
    fun `McpJournalLineInput converts ON_CHAIN entry correctly`() {
        val input = McpJournalLineInput(
            accountId = accountId.value.toString(),
            entryType = "CREDIT",
            monetaryEntryType = "ON_CHAIN",
            amount = "1000",
            token = "USDC",
            chainId = "EVM",
            txHash = "0xabc123",
            blockNumber = 19_000_000L,
            walletAddress = "0xwallet",
            tokenContract = "0xcontract",
        )
        val request = input.toJournalLineRequest()

        assertEquals(EntryType.CREDIT, request.entryType)
        val entry = request.monetaryEntry as OnChainEntry
        assertEquals(StablecoinToken.USDC, entry.token)
        assertEquals(ChainId.EVM, entry.chainId)
        assertEquals("0xabc123", entry.txHash)
    }

    @Test
    fun `McpJournalLineInput throws on unknown monetaryEntryType`() {
        val input = McpJournalLineInput(
            accountId = accountId.value.toString(),
            entryType = "DEBIT",
            monetaryEntryType = "CRYPTO",
            amount = "50",
        )
        assertFailsWith<IllegalArgumentException> {
            input.toJournalLineRequest()
        }
    }

    @Test
    fun `McpJournalLineInput throws when FIAT entry is missing currency`() {
        val input = McpJournalLineInput(
            accountId = accountId.value.toString(),
            entryType = "DEBIT",
            monetaryEntryType = "FIAT",
            amount = "100",
            rail = "WIRE",
        )
        assertFailsWith<IllegalArgumentException> {
            input.toJournalLineRequest()
        }
    }

    @Test
    fun `McpJournalLineInput throws when FIAT entry is missing rail`() {
        val input = McpJournalLineInput(
            accountId = accountId.value.toString(),
            entryType = "DEBIT",
            monetaryEntryType = "FIAT",
            amount = "100",
            currency = "BRL",
        )
        assertFailsWith<IllegalArgumentException> {
            input.toJournalLineRequest()
        }
    }

    @Test
    fun `McpJournalLineInput throws when ON_CHAIN entry is missing token`() {
        val input = McpJournalLineInput(
            accountId = accountId.value.toString(),
            entryType = "CREDIT",
            monetaryEntryType = "ON_CHAIN",
            amount = "50",
            chainId = "EVM",
            txHash = "0xabc",
            blockNumber = 1L,
            walletAddress = "0xw",
            tokenContract = "0xc",
        )
        assertFailsWith<IllegalArgumentException> {
            input.toJournalLineRequest()
        }
    }

    @Test
    fun `McpJournalLineInput throws when ON_CHAIN entry is missing txHash`() {
        val input = McpJournalLineInput(
            accountId = accountId.value.toString(),
            entryType = "CREDIT",
            monetaryEntryType = "ON_CHAIN",
            amount = "50",
            token = "USDC",
            chainId = "EVM",
            blockNumber = 1L,
            walletAddress = "0xw",
            tokenContract = "0xc",
        )
        assertFailsWith<IllegalArgumentException> {
            input.toJournalLineRequest()
        }
    }

    // ── rollbackWorkflow ──────────────────────────────────────────────────────

    @Test
    fun `rollbackWorkflow returns rollbackId and compensated steps on success`() {
        val planId = WorkflowPlanId.generate()
        val txId = TransactionId(UUID.randomUUID())
        val summary = RollbackWorkflowSummary(
            workflowPlanId = planId,
            compensatedSteps = listOf(CompensatedStepSummary(0, "Transfer funds", txId)),
            status = "ROLLED_BACK",
        )
        whenever(rollbackWorkflowUseCase.execute(any())).thenReturn(Result.success(summary))

        val result = server.rollbackWorkflow(
            workflowPlanId = planId.value.toString(),
            reason = "Test rollback",
            agentId = "agent-1",
            sessionId = "session-1",
        )

        assertEquals(planId.value.toString(), result.rollbackId)
        assertEquals("ROLLED_BACK", result.status)
        assertEquals(1, result.compensatedSteps.size)
        assertEquals(0, result.compensatedSteps[0].stepOrder)
        assertEquals("Transfer funds", result.compensatedSteps[0].description)
        assertEquals(txId.value.toString(), result.compensatedSteps[0].compensatingTransactionId)

        val captor = argumentCaptor<RollbackWorkflowCommand>()
        verify(rollbackWorkflowUseCase).execute(captor.capture())
        val cmd = captor.firstValue
        assertEquals(tenantId, cmd.tenantId)
        assertEquals(planId, cmd.workflowPlanId)
        assertEquals("Test rollback", cmd.reason)
        assertEquals("agent-1", cmd.agentContext.agentId)
        assertEquals("session-1", cmd.agentContext.sessionId)
    }

    @Test
    fun `rollbackWorkflow propagates failure`() {
        whenever(rollbackWorkflowUseCase.execute(any())).thenReturn(
            Result.failure(IllegalStateException("Cannot rollback PLANNED workflow"))
        )
        assertFailsWith<RuntimeException> {
            server.rollbackWorkflow(
                workflowPlanId = UUID.randomUUID().toString(),
                reason = "test",
                agentId = "agent",
                sessionId = "session",
            )
        }
    }

    // ── reconcileBatch ────────────────────────────────────────────────────────

    @Test
    fun `reconcileBatch maps result fields correctly`() {
        val reconcileResult = ReconcileEntriesResult(
            matched = 3,
            unmatched = 1,
            exceptions = listOf(ReconciliationException(UUID.randomUUID(), "0xabc", "No match")),
            settlementIds = listOf("id-1", "id-2", "id-3"),
        )
        whenever(reconcileEntriesUseCase.execute(any())).thenReturn(Result.success(reconcileResult))

        val result = server.reconcileBatch(
            accountId = null,
            from = "2025-01-01T00:00:00Z",
            to = "2025-01-31T23:59:59Z",
            tolerancePercent = null,
        )

        assertEquals(3, result.matched)
        assertEquals(1, result.unmatched)
        assertEquals(1, result.exceptions.size)
        assertEquals(3, result.settlementIds.size)
        assertEquals(listOf("id-1", "id-2", "id-3"), result.settlementIds)
    }

    @Test
    fun `reconcileBatch forwards tolerancePercent in command`() {
        whenever(reconcileEntriesUseCase.execute(any())).thenReturn(
            Result.success(ReconcileEntriesResult(0, 0, emptyList(), emptyList()))
        )

        server.reconcileBatch(
            accountId = accountId.value.toString(),
            from = "2025-01-01T00:00:00Z",
            to = "2025-01-31T23:59:59Z",
            tolerancePercent = 2.5,
        )

        val captor = argumentCaptor<ReconcileEntriesCommand>()
        verify(reconcileEntriesUseCase).execute(captor.capture())
        assertEquals(BigDecimal("2.5"), captor.firstValue.tolerancePercent)
        assertEquals(accountId, captor.firstValue.accountId)
    }

    // ── getAgentAuditLog ──────────────────────────────────────────────────────

    @Test
    fun `getAgentAuditLog returns mapped audit events`() {
        val eventId = UUID.randomUUID()
        val planId = UUID.randomUUID()
        val now = Instant.now()
        whenever(getAgentAuditLogUseCase.execute(any())).thenReturn(listOf(
            AgentAuditView(
                id = eventId,
                workflowPlanId = planId,
                agentId = "agent-99",
                sessionId = "sess-abc",
                eventType = "AGENT_ACTION_COMPLETED",
                intentPayload = "transfer funds",
                status = "COMPLETED",
                occurredAt = now,
                completedAt = now,
                hmacSignature = "hmac-base64-value",
            )
        ))

        val result = server.getAgentAuditLog(sessionId = "sess-abc", from = null, to = null, limit = null)

        assertEquals(1, result.total)
        assertEquals(1, result.auditEvents.size)
        val item = result.auditEvents[0]
        assertEquals(eventId.toString(), item.id)
        assertEquals(planId.toString(), item.workflowPlanId)
        assertEquals("agent-99", item.agentId)
        assertNull(item.model)
        assertEquals("sess-abc", item.sessionId)
        assertEquals("AGENT_ACTION_COMPLETED", item.eventType)
        assertEquals("transfer funds", item.intentPayload)
        assertEquals("COMPLETED", item.status)
        assertEquals(now.toString(), item.occurredAt)
        assertEquals(now.toString(), item.completedAt)
        assertEquals("hmac-base64-value", item.hmacSignature)

        val captor = argumentCaptor<GetAgentAuditLogQuery>()
        verify(getAgentAuditLogUseCase).execute(captor.capture())
        assertEquals("sess-abc", captor.firstValue.sessionId)
    }

    @Test
    fun `getAgentAuditLog clamps limit to 200`() {
        whenever(getAgentAuditLogUseCase.execute(any())).thenReturn(emptyList())

        server.getAgentAuditLog(sessionId = null, from = null, to = null, limit = 999)

        val captor = argumentCaptor<GetAgentAuditLogQuery>()
        verify(getAgentAuditLogUseCase).execute(captor.capture())
        assertEquals(200, captor.firstValue.limit)
    }

    @Test
    fun `getAgentAuditLog returns empty list when no events`() {
        whenever(getAgentAuditLogUseCase.execute(any())).thenReturn(emptyList())

        val result = server.getAgentAuditLog(sessionId = null, from = null, to = null, limit = null)

        assertEquals(0, result.total)
        assertEquals(0, result.auditEvents.size)

        val captor = argumentCaptor<GetAgentAuditLogQuery>()
        verify(getAgentAuditLogUseCase).execute(captor.capture())
        assertEquals(50, captor.firstValue.limit)
    }

    // ── describeAccount ───────────────────────────────────────────────────────

    @Test
    fun `describeAccount delegates to DescribeAccountUseCase and maps result`() {
        val balance = Balance(accountId, FiatCurrency.USD, MonetaryAmount.of("1000"), EntryType.DEBIT, Instant.now())
        val desc = AccountDescription(
            accountId = accountId,
            name = "Ops Account",
            description = null,
            currency = FiatCurrency.USD,
            entryCount = 42L,
            lastActivityAt = Instant.parse("2025-06-01T00:00:00Z"),
            balance = balance,
        )
        whenever(describeAccountUseCase.execute(any())).thenReturn(Result.success(desc))

        val result = server.describeAccount(accountId = accountId.value.toString())

        assertEquals(accountId.value.toString(), result.accountId)
        assertEquals("Ops Account", result.name)
        assertEquals("USD", result.currency)
        assertEquals(42L, result.entryCount)
        assertNotNull(result.lastActivityAt)
        assertEquals("1000", result.balanceAmount)

        val captor = argumentCaptor<DescribeAccountQuery>()
        verify(describeAccountUseCase).execute(captor.capture())
        assertEquals(accountId, captor.firstValue.accountId)
        assertEquals(tenantId, captor.firstValue.tenantId)
    }
}
