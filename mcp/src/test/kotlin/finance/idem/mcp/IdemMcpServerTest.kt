package finance.idem.mcp

import finance.idem.application.agentic.ExecuteWorkflowCommand
import finance.idem.application.agentic.ExecuteWorkflowUseCase
import finance.idem.application.ledger.AccountDescription
import finance.idem.application.ledger.Balance
import finance.idem.application.ledger.DescribeAccountQuery
import finance.idem.application.ledger.DescribeAccountUseCase
import finance.idem.application.ledger.EntryPage
import finance.idem.application.ledger.GetBalanceQuery
import finance.idem.application.ledger.GetBalanceUseCase
import finance.idem.application.ledger.GetEntriesQuery
import finance.idem.application.ledger.GetEntriesUseCase
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

@ExtendWith(MockitoExtension::class)
class IdemMcpServerTest {

    @Mock lateinit var executeWorkflowUseCase: ExecuteWorkflowUseCase
    @Mock lateinit var getBalanceUseCase: GetBalanceUseCase
    @Mock lateinit var getEntriesUseCase: GetEntriesUseCase
    @Mock lateinit var describeAccountUseCase: DescribeAccountUseCase

    private lateinit var server: IdemMcpServer

    private val tenantId = TenantId.generate()
    private val accountId = AccountId.generate()

    @BeforeEach
    fun setUp() {
        server = IdemMcpServer(executeWorkflowUseCase, getBalanceUseCase, getEntriesUseCase, describeAccountUseCase)
        val auth = TestingAuthenticationToken(tenantId, null, "AGENTS_EXECUTE")
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
