# Idem — MCP Server

> `mcp` module (`finance.idem.mcp`).
> Spring AI MCP server exposing ledger tools to AI agents (Claude, Cursor, any MCP-compatible runtime).

---

## Overview

The MCP server is Idem's primary agentic distribution surface. It wraps the core ledger use cases behind the [Model Context Protocol](https://modelcontextprotocol.io/) so any MCP-compatible AI agent can post transactions, query balances, list entries, and inspect account metadata — without writing custom API integrations.

> **Using Claude?** [`docs/skills/idem-ledger/SKILL.md`](skills/idem-ledger/SKILL.md) is the fast
> path — a lean, on-demand-loaded cheat sheet (connection snippet, tool/scope table, common call
> sequences). Install it instead of reading this whole doc if you just want to get an agent
> talking to Idem. Come back here for field-level schemas and the auth internals.

Every tool call goes through the same auth and domain-layer stack as a REST request:

```
AI agent (Claude Desktop / Claude Code / custom)
    │  MCP JSON-RPC 2.0 over SSE
    ▼
McpSseAuthBridgeFilter  → injects tenant auth into SecurityContext
    │
Spring Security (@PreAuthorize → AGENTS_EXECUTE scope required)
    │
IdemMcpServer (@Tool methods)
    │
PolicyGuard.evaluate()  ← policy rules loaded from PolicyRepository (per-tenant + per-agent)
    │
ExecuteWorkflowUseCase / GetBalanceUseCase / GetEntriesUseCase / DescribeAccountUseCase
    │
PostgreSQL (journal_lines, transactions, accounts)
```

---

## Transport

Spring AI's `WebMvcSseServerTransportProvider` exposes two endpoints on the main application port (`:8081`):

| Endpoint | Method | Purpose |
|---|---|---|
| `/sse` | `GET` | Opens the SSE stream; receives the `endpoint` event carrying the session ID |
| `/mcp/messages` | `POST` | Receives JSON-RPC 2.0 tool-call messages; `?sessionId=<uuid>` required |

Configured in `application.yaml`:

```yaml
spring:
  ai:
    mcp:
      server:
        name: idem-ledger
        version: 0.1.0
        transport: sse
```

---

## Implemented tools

Seven tools are exposed. Most require `AGENTS_EXECUTE`; `rollbackWorkflow` requires the
separate `AGENTS_ROLLBACK` scope, and `getAgentAuditLog` requires `AGENTS_AUDIT_READ`.

| Tool | Required scope |
|---|---|
| `postTransaction` | `AGENTS_EXECUTE` |
| `getBalance` | `AGENTS_EXECUTE` |
| `listEntries` | `AGENTS_EXECUTE` |
| `describeAccount` | `AGENTS_EXECUTE` |
| `reconcileBatch` | `AGENTS_EXECUTE` |
| `rollbackWorkflow` | `AGENTS_ROLLBACK` |
| `getAgentAuditLog` | `AGENTS_AUDIT_READ` |

### `postTransaction`

Posts a balanced double-entry transaction as an AI agent. Delegates to `ExecuteWorkflowUseCase`.

```
postTransaction(
    entries: List<McpJournalLineInput>,   // journal lines — see below
    idempotencyKey: String,               // 24h deduplicated; duplicate → cached result
    intentDescription: String?,           // human-readable intent for audit log
    agentId: String,                      // agent identity (e.g. "reconciliation-bot-v2")
    sessionId: String,                    // session grouping related actions
) → PostTransactionResult(workflowPlanId, status)
```

`McpJournalLineInput` fields:

| Field | Required | Notes |
|---|---|---|
| `accountId` | ✅ | UUID of the account |
| `entryType` | ✅ | `DEBIT` or `CREDIT` |
| `monetaryEntryType` | ✅ | `FIAT` or `ON_CHAIN` |
| `amount` | ✅ | Decimal string, e.g. `"1000.00"` |
| `currency` | FIAT only | ISO 4217: `BRL`, `USD`, `MXN`, `EUR` |
| `rail` | FIAT only | `ACH`, `WIRE`, `PIX`, `SWIFT`, `SEPA` |
| `bankReference` | optional | Bank-issued reference |
| `token` | ON_CHAIN only | `USDC`, `USDT`, `BRZ`, `PYUSD` |
| `chainId` | ON_CHAIN only | `EVM`, `SOLANA`, `TRON` |
| `txHash` | ON_CHAIN only | On-chain transaction hash |
| `blockNumber` | ON_CHAIN only | Block number |
| `walletAddress` | ON_CHAIN only | Receiving wallet address |
| `tokenContract` | ON_CHAIN only | ERC-20 / token contract address |

**Policy guard**: `PolicyGuard.evaluate()` fires before any write. Effective rules are loaded from `PolicyRepository` for the current tenant and agent key prefix. If no rules are configured, the default deny-all (`MaxDebitPerSession(ZERO)`) blocks every debit until an admin configures at least one permissive rule via `POST /api/v1/admin/policy-rules`.

**Audit log**: the authenticated API key prefix (`Authentication.getName()`) is captured from `SecurityContextHolder` and stored in `AgentContext.apiKeyPrefix` for an agent-originated trace in the audit log.

---

### `getBalance`

Returns the current balance for an account. Accepts an optional ISO-8601 instant for point-in-time queries.

```
getBalance(
    accountId: String,   // account UUID
    asOf: String?,       // optional ISO-8601 instant, e.g. "2025-12-31T23:59:59Z"
) → BalanceResult(accountId, currency, amount, computedAt)
```

---

### `listEntries`

Lists journal entries for an account, newest first. Supports time-range filtering and cursor-based pagination.

```
listEntries(
    accountId: String,   // account UUID
    from: String?,       // ISO-8601 lower bound (inclusive)
    to: String?,         // ISO-8601 upper bound (inclusive)
    limit: Int?,         // 1–200, default 50
    cursor: String?,     // opaque cursor from previous page's nextCursor
) → EntryListResult(accountId, entries: List<EntryItem>, nextCursor?)
```

`EntryItem`: `id`, `transactionId`, `entryType`, `amount`, `currency` (ISO 4217 for fiat, token name for on-chain), `description?`, `createdAt`.

---

### `describeAccount`

Returns account metadata and current balance in a single call.

```
describeAccount(
    accountId: String,   // account UUID
) → AccountDescriptionResult(
      accountId, name, description?, currency,
      entryCount, lastActivityAt?,
      balanceCurrency, balanceAmount
    )
```

---

### `rollbackWorkflow`

Rolls back a committed or executing workflow via compensating transactions (saga pattern):
each executed step is reversed in reverse order. Delegates to `RollbackWorkflowUseCase`.
Requires the `AGENTS_ROLLBACK` scope, deliberately separate from `AGENTS_EXECUTE` — an agent
authorized to commit transactions cannot roll them back unless explicitly granted this scope.

```
rollbackWorkflow(
    workflowPlanId: String,   // WorkflowPlan UUID to roll back
    reason: String,           // human-readable reason, recorded in the audit log
    agentId: String,          // agent identity
    sessionId: String,        // session grouping related actions
) → RollbackWorkflowResult(rollbackId, compensatedSteps: List<CompensatedStepItem>, status)
```

`CompensatedStepItem`: `stepOrder`, `description`, `compensatingTransactionId?`.

Compensating transactions post directly via `PostTransactionUseCase`, bypassing `PolicyGuard`
by design (a rollback is a corrective action, not a new agent-initiated debit). The workflow
plan transitions to a terminal `ROLLED_BACK` status on success.

---

### `reconcileBatch`

Runs a reconciliation sweep over on-chain settlements within a time window, matching
`UNMATCHED` chain entries against `PENDING` journal lines by amount. Delegates to
`ReconcileEntriesUseCase`.

```
reconcileBatch(
    accountId: String?,          // optional account UUID to scope the sweep
    from: String,                // ISO-8601 lower bound on settlement timestamp
    to: String,                  // ISO-8601 upper bound on settlement timestamp
    tolerancePercent: Double?,   // optional per-call override of the server default
) → ReconcileBatchResult(matched, unmatched, exceptions: List<String>, settlementIds: List<String>)
```

Matching is exact by default; `tolerancePercent` (or the server-wide
`idem.reconciliation.amount-tolerance-percent` property) allows a bounded amount difference.
Entries with no matching `PENDING` candidate within tolerance surface in `exceptions` with a
`"No matching pending settlement found"` reason and are not settled.

---

### `getAgentAuditLog`

Retrieves HMAC-signed audit events for agent actions, filterable by session and time range.
Delegates to `GetAgentAuditLogUseCase`. Requires the separate `AGENTS_AUDIT_READ` scope.

```
getAgentAuditLog(
    sessionId: String?,   // optional session identifier filter
    from: String?,        // optional ISO-8601 lower bound
    to: String?,          // optional ISO-8601 upper bound
    limit: Int?,          // 1-200, default 50
) → AuditLogResult(auditEvents: List<AuditEventItem>, total)
```

`AuditEventItem`: `id`, `workflowPlanId`, `agentId`, `sessionId`, `eventType`, `intentPayload`,
`status`, `occurredAt`, `completedAt?`, `hmacSignature`. Every mutating tool call writes a
`PENDING` `AgentAuditEvent` **before** execution and a `COMPLETED`/`FAILED` event after —
`AgentAuditEvent` is append-only and each event's `hmacSignature` can be independently
re-verified against the tenant's audit HMAC secret.

---

## Auth model

### API key requirement

Every MCP connection requires an API key with `AGENTS_EXECUTE` scope. The recommended key type is `sk_agent_` prefix (agent-restricted scopes); `sk_live_` keys that include `AGENTS_EXECUTE` also work.

The key is sent as:

```
X-API-Key: sk_agent_...
```

or

```
Authorization: Bearer sk_agent_...
```

`ApiKeyAuthFilter` validates the key (bcrypt hash + Redis cache), extracts the `tenant_id`, and populates `SecurityContextHolder` with `ApiKeyAuthentication(tenantId, keyPrefix, authorities)`.

### The SSE session auth bridge

`mcp-remote` (and most MCP clients) send the API key header **only on the initial `GET /sse`** request, not on subsequent `POST /mcp/messages` requests. This is a structural property of the SSE session model — once the stream is open, POST messages are keyed only by `?sessionId=<uuid>`.

`McpSseAuthBridgeFilter` (added after `ApiKeyAuthFilter` in the security filter chain) bridges this gap:

**On `GET /sse`** (API key authenticated):
1. Wraps the response with `SessionCapturingResponseWrapper`.
2. `SessionCapturingOutputStream` buffers SSE writes, scanning for the `endpoint` event that Spring AI emits first — it contains the session ID as `sessionId=<uuid>`.
3. Once captured, registers `sessionId → Authentication` in `McpSseSessionAuthStore` (in-process `ConcurrentHashMap`).
4. All I/O is passed through to the underlying stream in real time (including `flush()`) — the SSE connection stays alive.

**On `POST /mcp/messages?sessionId=<uuid>`** (no API key header):
1. Reads the `sessionId` query param.
2. Looks up the stored `Authentication` from `McpSseSessionAuthStore`.
3. Injects it into `SecurityContextHolder` before `@PreAuthorize` runs.

```
GET /sse                    POST /mcp/messages?sessionId=abc123
X-API-Key: sk_agent_...     (no API key header)
    │                               │
ApiKeyAuthFilter ────────►  (no auth established)
    │                               │
McpSseAuthBridgeFilter:     McpSseAuthBridgeFilter:
  wraps response              reads sessionId param
  captures sessionId          looks up store[abc123]
  stores auth                 injects auth into context
```

`McpSseSessionAuthStore` holds `Authentication` objects in memory — correct for SSE sessions, which are inherently local to one JVM pod. Multi-replica deployments must use sticky sessions at the load balancer (no shared store is needed, and `Authentication` is not trivially serializable).

### Reactor context propagation

Spring AI's `McpAsyncServer` dispatches tool execution **asynchronously on a Reactor scheduler thread**, not on the HTTP POST handler thread. `SecurityContextHolder` uses `ThreadLocal`, so the auth injected by `McpSseAuthBridgeFilter` on the POST thread would be invisible to the Reactor worker — making `@PreAuthorize` and `tenantId()` fail with "no authentication in SecurityContext."

`McpReactorSecurityConfig` fixes this at startup via `@PostConstruct`:

```kotlin
ContextRegistry.getInstance().registerThreadLocalAccessor(SecurityContextAccessor())
Hooks.enableAutomaticContextPropagation()
```

`SecurityContextAccessor` implements `ThreadLocalAccessor<SecurityContext>` (Micrometer context-propagation API). When `Hooks.enableAutomaticContextPropagation()` is active, Reactor captures a snapshot of all registered `ThreadLocal` values at subscription time (while the HTTP POST handler is still running and auth IS set), and restores that snapshot whenever it switches threads. The result: `@PreAuthorize` and `SecurityContextHolder.getContext().authentication` work correctly on any Reactor thread.

---

## Connecting Claude Desktop

**Prerequisites:** ngrok (or any tunnel), `mcp-remote` (`npm install -g mcp-remote`).

1. Start the app and expose it:
   ```bash
   ./mvnw spring-boot:run -pl app -Dspring-boot.run.profiles=dev
   ngrok http 8081
   ```

2. Create an agent-scoped API key (requires an `ADMIN` key):
   ```bash
   curl -X POST http://localhost:8081/api/v1/api-keys \
     -H "X-API-Key: $IDEM_ADMIN_KEY" \
     -H "Content-Type: application/json" \
     -d '{"scopes":["AGENTS_EXECUTE","ACCOUNTS_READ"]}'
   ```
   Copy the `rawKey` value — it is shown exactly once.

3. Add to `claude_desktop_config.json` (macOS: `~/Library/Application Support/Claude/`):
   ```json
   {
     "mcpServers": {
       "idem": {
         "command": "npx",
         "args": ["-y", "mcp-remote", "https://<your-ngrok-id>.ngrok.io/sse"],
         "env": {
           "MCP_HEADER_X_API_KEY": "sk_agent_..."
         }
       }
     }
   }
   ```

4. Restart Claude Desktop. The seven Idem tools appear in the tool list.

---

## Connecting Claude Code

```bash
claude mcp add idem -- npx -y mcp-remote https://<your-ngrok-id>.ngrok.io/sse
# then set the API key header via env or mcp-remote --header flag
```

---

## Module layout

```
mcp/
└── src/main/kotlin/finance/idem/mcp/
    ├── IdemMcpServer.kt          ← @Component with @Tool methods
    ├── McpToolsConfig.kt         ← registers IdemMcpServer as ToolCallbackProvider
    ├── McpJournalLineInput.kt    ← input DTO (in IdemMcpServer.kt)
    ├── PostTransactionResult.kt
    ├── BalanceResult.kt
    ├── EntryItem.kt
    ├── EntryListResult.kt
    ├── AccountDescriptionResult.kt
    ├── RollbackWorkflowResult.kt  ← includes CompensatedStepItem
    ├── ReconcileBatchResult.kt
    └── AuditLogResult.kt          ← includes AuditEventItem

infrastructure/src/main/kotlin/finance/idem/infrastructure/security/
    ├── McpSseAuthBridgeFilter.kt    ← SSE session ↔ API-key auth bridge
    ├── McpSseSessionAuthStore.kt    ← ConcurrentHashMap[sessionId → Authentication]
    └── McpReactorSecurityConfig.kt  ← Reactor context propagation for @PreAuthorize
```

---

## Known limitations

- **Session store**: `McpSseSessionAuthStore` is in-process only. In multi-replica GKE deployments, configure sticky sessions (e.g., GCP Cloud Load Balancing session affinity) so that GET /sse and subsequent POST /mcp/messages reach the same pod.
- **Session cleanup**: Sessions are not currently removed from the store on SSE disconnect. In long-running deployments this is a small bounded memory leak (one `Authentication` object per historical session). A future cleanup on SSE close event is straightforward.

---

## Related

- `docs/policy-guard.md` — PolicyGuard, PolicyRule variants, evaluation pipeline
- `docs/domain-model.md` — AgentContext, MonetaryEntry, Transaction
- `infrastructure/security/McpSseAuthBridgeFilter.kt` — SSE session auth bridge
- `infrastructure/security/McpReactorSecurityConfig.kt` — Reactor context propagation
- Issue [#166](https://github.com/idem-finance/idem/issues/166) — MCP tools implementation
