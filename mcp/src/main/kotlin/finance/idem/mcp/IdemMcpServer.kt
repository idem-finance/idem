package finance.idem.mcp

import finance.idem.application.agentic.ExecuteWorkflowCommand
import finance.idem.application.agentic.ExecuteWorkflowUseCase
import finance.idem.application.agentic.WorkflowStepCommand
import finance.idem.application.ledger.DescribeAccountQuery
import finance.idem.application.ledger.DescribeAccountUseCase
import finance.idem.application.ledger.GetBalanceQuery
import finance.idem.application.ledger.GetBalanceUseCase
import finance.idem.application.ledger.GetEntriesQuery
import finance.idem.application.ledger.GetEntriesUseCase
import finance.idem.application.ledger.JournalLineRequest
import finance.idem.core.AccountId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.ChainId
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.agentic.AgentContext
import finance.idem.core.monetary.FiatEntry
import finance.idem.core.monetary.OnChainEntry
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class IdemMcpServer(
    private val executeWorkflowUseCase: ExecuteWorkflowUseCase,
    private val getBalanceUseCase: GetBalanceUseCase,
    private val getEntriesUseCase: GetEntriesUseCase,
    private val describeAccountUseCase: DescribeAccountUseCase,
) {

    @Tool(description = "Post a double-entry ledger transaction as an AI agent. Requires AGENTS_EXECUTE scope. Policy rules are evaluated before commit; a PolicyViolationException is thrown if any rule is violated.")
    fun postTransaction(
        @ToolParam(description = "Journal lines: each line specifies accountId, entryType (DEBIT/CREDIT), monetaryEntryType (FIAT/ON_CHAIN), amount, and type-specific fields") entries: List<McpJournalLineInput>,
        @ToolParam(description = "Idempotency key — duplicate calls with the same key return the cached result") idempotencyKey: String,
        @ToolParam(description = "Human-readable intent description for the audit log") intentDescription: String?,
        @ToolParam(description = "Agent identifier from the calling agent's credentials") agentId: String,
        @ToolParam(description = "Session identifier grouping related agent actions") sessionId: String,
    ): PostTransactionResult {
        val tenantId = tenantId()
        val agentContext = AgentContext(agentId = agentId, sessionId = sessionId, intent = intentDescription)
        val cmd = ExecuteWorkflowCommand(
            tenantId = tenantId,
            agentContext = agentContext,
            steps = listOf(
                WorkflowStepCommand(
                    idempotencyKey = idempotencyKey,
                    lines = entries.map { it.toJournalLineRequest() },
                ),
            ),
            policyRules = emptyList(),
            createdBy = agentId,
        )
        return executeWorkflowUseCase.execute(cmd).fold(
            onSuccess = { planId -> PostTransactionResult(workflowPlanId = planId.value.toString(), status = "COMMITTED") },
            onFailure = { throw it },
        )
    }

    @Tool(description = "Get the current balance for an account. Optionally pass asOf (ISO-8601 instant) to compute a historical balance. Requires AGENTS_EXECUTE scope.")
    fun getBalance(
        @ToolParam(description = "Account UUID") accountId: String,
        @ToolParam(description = "Optional ISO-8601 instant to compute balance as of that point in time") asOf: String?,
    ): BalanceResult {
        val query = GetBalanceQuery(
            accountId = AccountId.of(accountId),
            tenantId = tenantId(),
            asOf = asOf?.let { Instant.parse(it) },
        )
        return getBalanceUseCase.execute(query).fold(
            onSuccess = { balance ->
                BalanceResult(
                    accountId = balance.accountId.value.toString(),
                    currency = balance.currency.name,
                    amount = balance.amount.value.toPlainString(),
                    computedAt = balance.computedAt.toString(),
                )
            },
            onFailure = { throw it },
        )
    }

    @Tool(description = "List journal entries for an account, newest first. Supports time-range filtering and cursor-based pagination. Requires AGENTS_EXECUTE scope.")
    fun listEntries(
        @ToolParam(description = "Account UUID") accountId: String,
        @ToolParam(description = "Optional inclusive lower bound on entry timestamp (ISO-8601)") from: String?,
        @ToolParam(description = "Optional inclusive upper bound on entry timestamp (ISO-8601)") to: String?,
        @ToolParam(description = "Max entries per page (1-200, default 50)") limit: Int?,
        @ToolParam(description = "Opaque cursor from a previous page's nextCursor field") cursor: String?,
    ): EntryListResult {
        val query = GetEntriesQuery(
            accountId = AccountId.of(accountId),
            tenantId = tenantId(),
            from = from?.let { Instant.parse(it) },
            to = to?.let { Instant.parse(it) },
            limit = (limit ?: 50).coerceIn(1, 200),
            cursor = cursor,
        )
        return getEntriesUseCase.execute(query).fold(
            onSuccess = { page ->
                EntryListResult(
                    accountId = page.accountId.value.toString(),
                    entries = page.entries.map { line ->
                        EntryItem(
                            id = line.id.toString(),
                            transactionId = line.transactionId.value.toString(),
                            entryType = line.entryType.name,
                            amount = line.monetaryEntry.amount.value.toPlainString(),
                            currency = when (val me = line.monetaryEntry) {
                                is FiatEntry -> me.currency.name
                                is OnChainEntry -> me.token.name
                            },
                            description = line.description,
                            createdAt = line.createdAt.toString(),
                        )
                    },
                    nextCursor = page.nextCursor,
                )
            },
            onFailure = { throw it },
        )
    }

    @Tool(description = "Describe an account: returns name, currency, entry count, last activity timestamp, and current balance. Requires AGENTS_EXECUTE scope.")
    fun describeAccount(
        @ToolParam(description = "Account UUID") accountId: String,
    ): AccountDescriptionResult {
        val query = DescribeAccountQuery(
            accountId = AccountId.of(accountId),
            tenantId = tenantId(),
        )
        return describeAccountUseCase.execute(query).fold(
            onSuccess = { desc ->
                AccountDescriptionResult(
                    accountId = desc.accountId.value.toString(),
                    name = desc.name,
                    description = desc.description,
                    currency = desc.currency.name,
                    entryCount = desc.entryCount,
                    lastActivityAt = desc.lastActivityAt?.toString(),
                    balanceCurrency = desc.balance.currency.name,
                    balanceAmount = desc.balance.amount.value.toPlainString(),
                )
            },
            onFailure = { throw it },
        )
    }

    private fun tenantId(): TenantId =
        SecurityContextHolder.getContext().authentication?.principal as? TenantId
            ?: throw IllegalStateException("No authenticated tenant in SecurityContext")
}

// ── Input DTO ──────────────────────────────────────────────────────────────────

data class McpJournalLineInput(
    val accountId: String,
    val entryType: String,
    val monetaryEntryType: String,
    val amount: String,
    val currency: String? = null,
    val rail: String? = null,
    val bankReference: String? = null,
    val token: String? = null,
    val chainId: String? = null,
    val txHash: String? = null,
    val blockNumber: Long? = null,
    val walletAddress: String? = null,
    val tokenContract: String? = null,
) {
    fun toJournalLineRequest(): JournalLineRequest {
        val monetaryEntry = when (monetaryEntryType.uppercase()) {
            "FIAT" -> FiatEntry(
                amount = MonetaryAmount.of(amount),
                currency = FiatCurrency.valueOf(requireNotNull(currency) { "currency required for FIAT entry" }.uppercase()),
                rail = PaymentRail.valueOf(requireNotNull(rail) { "rail required for FIAT entry" }.uppercase()),
                bankReference = bankReference,
            )
            "ON_CHAIN" -> OnChainEntry(
                amount = MonetaryAmount.of(amount),
                token = StablecoinToken.valueOf(requireNotNull(token) { "token required for ON_CHAIN entry" }.uppercase()),
                chainId = ChainId.valueOf(requireNotNull(chainId) { "chainId required for ON_CHAIN entry" }.uppercase()),
                txHash = requireNotNull(txHash) { "txHash required for ON_CHAIN entry" },
                blockNumber = requireNotNull(blockNumber) { "blockNumber required for ON_CHAIN entry" },
                walletAddress = requireNotNull(walletAddress) { "walletAddress required for ON_CHAIN entry" },
                tokenContract = requireNotNull(tokenContract) { "tokenContract required for ON_CHAIN entry" },
            )
            else -> throw IllegalArgumentException("Unknown monetaryEntryType: $monetaryEntryType")
        }
        return JournalLineRequest(
            accountId = AccountId.of(accountId),
            entryType = EntryType.valueOf(entryType.uppercase()),
            monetaryEntry = monetaryEntry,
        )
    }
}

// ── Result DTOs ────────────────────────────────────────────────────────────────

data class PostTransactionResult(
    val workflowPlanId: String,
    val status: String,
)

data class BalanceResult(
    val accountId: String,
    val currency: String,
    val amount: String,
    val computedAt: String,
)

data class EntryItem(
    val id: String,
    val transactionId: String,
    val entryType: String,
    val amount: String,
    val currency: String,
    val description: String?,
    val createdAt: String,
)

data class EntryListResult(
    val accountId: String,
    val entries: List<EntryItem>,
    val nextCursor: String?,
)

data class AccountDescriptionResult(
    val accountId: String,
    val name: String,
    val description: String?,
    val currency: String,
    val entryCount: Long,
    val lastActivityAt: String?,
    val balanceCurrency: String,
    val balanceAmount: String,
)
