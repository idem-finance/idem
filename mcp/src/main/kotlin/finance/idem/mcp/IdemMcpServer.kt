package finance.idem.mcp

import finance.idem.application.agentic.ExecuteWorkflowCommand
import finance.idem.application.agentic.ExecuteWorkflowUseCase
import finance.idem.application.agentic.RollbackWorkflowCommand
import finance.idem.application.agentic.RollbackWorkflowUseCase
import finance.idem.application.agentic.WorkflowStepCommand
import finance.idem.application.ledger.DescribeAccountQuery
import finance.idem.application.ledger.DescribeAccountUseCase
import finance.idem.application.ledger.GetBalanceQuery
import finance.idem.application.ledger.GetBalanceUseCase
import finance.idem.application.ledger.GetEntriesQuery
import finance.idem.application.ledger.GetEntriesUseCase
import finance.idem.application.ledger.JournalLineRequest
import finance.idem.application.port.AgentAuditRepository
import finance.idem.application.reconciliation.ReconcileEntriesCommand
import finance.idem.application.reconciliation.ReconcileEntriesUseCase
import finance.idem.core.AccountId
import finance.idem.core.ChainId
import finance.idem.core.EntryType
import finance.idem.core.FiatCurrency
import finance.idem.core.MonetaryAmount
import finance.idem.core.PaymentRail
import finance.idem.core.StablecoinToken
import finance.idem.core.TenantId
import finance.idem.core.WorkflowPlanId
import finance.idem.core.agentic.AgentContext
import finance.idem.core.agentic.PolicyViolationException
import finance.idem.core.monetary.FiatEntry
import finance.idem.core.monetary.OnChainEntry
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.format.DateTimeParseException

@Component
class IdemMcpServer(
    private val executeWorkflowUseCase: ExecuteWorkflowUseCase,
    private val getBalanceUseCase: GetBalanceUseCase,
    private val getEntriesUseCase: GetEntriesUseCase,
    private val describeAccountUseCase: DescribeAccountUseCase,
    private val rollbackWorkflowUseCase: RollbackWorkflowUseCase,
    private val reconcileEntriesUseCase: ReconcileEntriesUseCase,
    private val agentAuditRepository: AgentAuditRepository,
) {
    private val log = LoggerFactory.getLogger(IdemMcpServer::class.java)

    @PreAuthorize("hasAuthority('AGENTS_EXECUTE')")
    @Tool(description = "Post a double-entry ledger transaction as an AI agent. Requires AGENTS_EXECUTE scope. Policy rules are evaluated before commit; a PolicyViolationException is thrown if any rule is violated.")
    fun postTransaction(
        @ToolParam(description = "Journal lines: each line specifies accountId, entryType (DEBIT/CREDIT), monetaryEntryType (FIAT/ON_CHAIN), amount, and type-specific fields") entries: List<McpJournalLineInput>,
        @ToolParam(description = "Idempotency key — duplicate calls with the same key return the cached result") idempotencyKey: String,
        @ToolParam(description = "Human-readable intent description for the audit log") intentDescription: String?,
        @ToolParam(description = "Agent identifier from the calling agent's credentials") agentId: String,
        @ToolParam(description = "Session identifier grouping related agent actions") sessionId: String,
    ): PostTransactionResult {
        val tenantId = tenantId()
        // Extract the verified API key prefix from the authenticated principal so the audit log
        // can trace this action to an actual credential, independent of the caller-supplied agentId.
        val apiKeyPrefix = SecurityContextHolder.getContext().authentication?.name
        val agentContext = AgentContext(
            agentId = agentId,
            sessionId = sessionId,
            intent = intentDescription,
            apiKeyPrefix = apiKeyPrefix,
        )
        val cmd = ExecuteWorkflowCommand(
            tenantId = tenantId,
            agentContext = agentContext,
            steps = listOf(
                WorkflowStepCommand(
                    idempotencyKey = idempotencyKey,
                    lines = entries.map { it.toJournalLineRequest() },
                ),
            ),
            policyRules = emptyList(), // TODO(#200): load from PolicyRepository once implemented
            createdBy = agentId,
        )
        return executeWorkflowUseCase.execute(cmd).fold(
            onSuccess = { planId -> PostTransactionResult(workflowPlanId = planId.value.toString(), status = "COMMITTED") },
            onFailure = { handleFailure(it) },
        )
    }

    @PreAuthorize("hasAuthority('AGENTS_EXECUTE')")
    @Tool(description = "Get the current balance for an account. Optionally pass asOf (ISO-8601 instant) to compute a historical balance. Requires AGENTS_EXECUTE scope.")
    fun getBalance(
        @ToolParam(description = "Account UUID") accountId: String,
        @ToolParam(description = "Optional ISO-8601 instant to compute balance as of that point in time") asOf: String?,
    ): BalanceResult {
        val query = GetBalanceQuery(
            accountId = AccountId.of(accountId),
            tenantId = tenantId(),
            asOf = asOf?.let { parseInstant(it, "asOf") },
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
            onFailure = { handleFailure(it) },
        )
    }

    @PreAuthorize("hasAuthority('AGENTS_EXECUTE')")
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
            from = from?.let { parseInstant(it, "from") },
            to = to?.let { parseInstant(it, "to") },
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
            onFailure = { handleFailure(it) },
        )
    }

    @PreAuthorize("hasAuthority('AGENTS_EXECUTE')")
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
            onFailure = { handleFailure(it) },
        )
    }

    @PreAuthorize("hasAuthority('AGENTS_EXECUTE')")
    @Tool(description = "Roll back a committed or executing workflow using compensating transactions (saga pattern). Each executed step is reversed in reverse order. Requires AGENTS_EXECUTE scope.")
    fun rollbackWorkflow(
        @ToolParam(description = "WorkflowPlan UUID to roll back") workflowPlanId: String,
        @ToolParam(description = "Human-readable reason for the rollback, recorded in the audit log") reason: String,
        @ToolParam(description = "Agent identifier from the calling agent's credentials") agentId: String,
        @ToolParam(description = "Session identifier grouping related agent actions") sessionId: String,
    ): RollbackWorkflowResult {
        val apiKeyPrefix = SecurityContextHolder.getContext().authentication?.name
        val cmd = RollbackWorkflowCommand(
            tenantId = tenantId(),
            agentContext = AgentContext(agentId = agentId, sessionId = sessionId, apiKeyPrefix = apiKeyPrefix),
            workflowPlanId = WorkflowPlanId(UUID.fromString(workflowPlanId)),
            reason = reason,
            createdBy = agentId,
        )
        return rollbackWorkflowUseCase.execute(cmd).fold(
            onSuccess = { summary ->
                RollbackWorkflowResult(
                    rollbackId = summary.workflowPlanId.value.toString(),
                    compensatedSteps = summary.compensatedSteps.map {
                        CompensatedStepItem(it.stepOrder, it.description, it.compensatingTransactionId?.value?.toString())
                    },
                    status = summary.status,
                )
            },
            onFailure = { handleFailure(it) },
        )
    }

    @PreAuthorize("hasAuthority('AGENTS_EXECUTE')")
    @Tool(description = "Run a reconciliation sweep over on-chain settlements within a time window. Matches UNMATCHED chain entries against PENDING journal lines by amount. Requires AGENTS_EXECUTE scope.")
    fun reconcileBatch(
        @ToolParam(description = "Optional account UUID to scope the reconciliation") accountId: String?,
        @ToolParam(description = "Inclusive lower bound on settlement timestamp (ISO-8601)") from: String,
        @ToolParam(description = "Inclusive upper bound on settlement timestamp (ISO-8601)") to: String,
        @ToolParam(description = "Optional per-call amount tolerance percentage, overrides the server default") tolerancePercent: Double?,
    ): ReconcileBatchResult {
        val cmd = ReconcileEntriesCommand(
            tenantId = tenantId(),
            accountId = accountId?.let { AccountId.of(it) },
            from = parseInstant(from, "from"),
            to = parseInstant(to, "to"),
            tolerancePercent = tolerancePercent,
        )
        return reconcileEntriesUseCase.execute(cmd).fold(
            onSuccess = { result ->
                ReconcileBatchResult(
                    matched = result.matched,
                    unmatched = result.unmatched,
                    exceptions = result.exceptions.map { "${it.txHash ?: it.settlementId}: ${it.reason}" },
                    settlementIds = result.settlementIds,
                )
            },
            onFailure = { handleFailure(it) },
        )
    }

    @PreAuthorize("hasAuthority('AGENTS_AUDIT_READ')")
    @Tool(description = "Retrieve HMAC-signed audit events for agent actions. Filterable by session, time range, and count. Each event includes hmacSignature for integrity verification. Requires AGENTS_AUDIT_READ scope.")
    fun getAgentAuditLog(
        @ToolParam(description = "Optional session identifier to filter events") sessionId: String?,
        @ToolParam(description = "Optional lower bound on event timestamp (ISO-8601)") from: String?,
        @ToolParam(description = "Optional upper bound on event timestamp (ISO-8601)") to: String?,
        @ToolParam(description = "Max events to return (1-200, default 50)") limit: Int?,
    ): AuditLogResult {
        val events = agentAuditRepository.findByFilter(
            tenantId = tenantId(),
            sessionId = sessionId,
            from = from?.let { parseInstant(it, "from") },
            to = to?.let { parseInstant(it, "to") },
            limit = (limit ?: 50).coerceIn(1, 200),
        )
        val items = events.map {
            AuditEventItem(
                id = it.id.toString(),
                workflowPlanId = it.workflowPlanId.toString(),
                agentId = it.agentId,
                model = null,
                sessionId = it.sessionId,
                eventType = it.eventType,
                intentPayload = it.intentPayload,
                status = it.status,
                occurredAt = it.occurredAt.toString(),
                completedAt = it.completedAt?.toString(),
                hmacSignature = it.hmacSignature,
            )
        }
        return AuditLogResult(auditEvents = items, total = items.size)
    }

    private fun tenantId(): TenantId =
        SecurityContextHolder.getContext().authentication?.principal as? TenantId
            ?: throw AccessDeniedException("No authenticated tenant in SecurityContext")

    private fun parseInstant(value: String, paramName: String): Instant =
        try {
            Instant.parse(value)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("Invalid ISO-8601 instant for '$paramName': $value")
        }

    private fun handleFailure(error: Throwable): Nothing = when (error) {
        is PolicyViolationException -> throw error
        is IllegalArgumentException -> throw error
        else -> {
            log.error("MCP tool execution failed", error)
            throw RuntimeException("Tool execution failed")
        }
    }
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
