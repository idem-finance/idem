# MCP demo video — script & recording checklist

> Tracks issue [#186](https://github.com/idem-finance/idem/issues/186). This is the launch
> demo video script, versioned here so it stays in sync with the actual MCP tool surface
> documented in [`docs/mcp-server.md`](mcp-server.md).

All three MCP tools this script calls (`reconcile_batch`, `rollback_workflow`,
`get_agent_audit_log`) are fully implemented — see `mcp/src/main/kotlin/finance/idem/mcp/IdemMcpServer.kt`
and the end-to-end `McpServerIntegrationTest` "demo scenario" test. Nothing in this script is
blocked on unbuilt functionality.

---

## Narration timeline (2:00–2:30 total)

| Time | Beat |
|---|---|
| 0:00 | "Idem is an open-source ledger for stablecoin cross-border payments." |
| 0:10 | Open Claude Code. Show `CLAUDE.md` context loaded. |
| 0:20 | "I will ask Claude to reconcile a batch of USDC transfers." |
| 0:30 | Claude Code calls `reconcile_batch` via MCP (show tool call + response) |
| 0:50 | "Two entries matched. One exception — amount mismatch. Rolling back." |
| 1:10 | Claude Code calls `rollback_workflow` via MCP (show compensating transactions in DB) |
| 1:30 | "The audit trail — every agent action logged before execution, HMAC-signed." |
| 1:45 | Show `get_agent_audit_log` response |
| 2:00 | "Open source. Run it yourself in 5 minutes." Show `docker compose up`, first transaction curl |
| 2:30 | "github.com/idem-finance/idem — star it." |

---

## Pre-recording setup (off-camera)

The script's first on-screen tool call is `reconcile_batch`, and `rollback_workflow` at 1:10
rolls back a **pre-existing** workflow — so the transaction being reconciled/rolled back must
already exist before recording starts. None of this setup is automated (no seed script exists
for this scenario yet); do it by hand once, then keep the terminal/DB state for the take.

1. **Start the stack and seed the dev tenant** (per `README.md` Quick start):
   ```bash
   make up
   make build
   make seed   # prints IDEM_API_KEY=sk_live_... (ADMIN scope) — save it
   ./mvnw spring-boot:run -pl app -Dspring-boot.run.profiles=dev
   ```

2. **Create two accounts** (`POST /api/v1/accounts`, requires `ACCOUNTS_WRITE`):
   ```bash
   curl -X POST http://localhost:8081/api/v1/accounts \
     -H "X-API-Key: $IDEM_API_KEY" -H "Content-Type: application/json" \
     -d '{"name":"Demo Fiat Cash","currency":"BRL","type":"ASSET"}'

   curl -X POST http://localhost:8081/api/v1/accounts \
     -H "X-API-Key: $IDEM_API_KEY" -H "Content-Type: application/json" \
     -d '{"name":"Demo USDC Wallet","currency":"USD","type":"ASSET"}'
   ```
   Save both `accountId` values from the responses.

3. **Configure a permissive policy rule** — without this, `PolicyGuard`'s default deny-all
   (`MaxDebitPerSession(ZERO)`) blocks every agent debit (see `docs/policy-guard.md`):
   ```bash
   curl -X POST http://localhost:8081/api/v1/admin/policy-rules \
     -H "X-API-Key: $IDEM_API_KEY" -H "Content-Type: application/json" \
     -d '{"type":"MAX_DEBIT_PER_SESSION","amount":"10000.00"}'
   ```

4. **Create an agent-scoped API key** with all three scopes the script needs
   (`AGENTS_EXECUTE` for `reconcile_batch`, `AGENTS_ROLLBACK` for `rollback_workflow`,
   `AGENTS_AUDIT_READ` for `get_agent_audit_log`):
   ```bash
   curl -X POST http://localhost:8081/api/v1/api-keys \
     -H "X-API-Key: $IDEM_API_KEY" -H "Content-Type: application/json" \
     -d '{"scopes":["AGENTS_EXECUTE","AGENTS_ROLLBACK","AGENTS_AUDIT_READ"]}'
   ```
   Save the `rawKey` — shown once. This is the key Claude Code/Desktop authenticates with over
   MCP (see `docs/mcp-server.md` → "Connecting Claude Code" / "Connecting Claude Desktop").

5. **Post the transaction that will later be rolled back**, via the `post_transaction` MCP
   tool (or the equivalent `POST /api/v1/transactions` REST call) — this produces the
   `workflowPlanId` used at 1:10:
   ```
   post_transaction(
       entries: [
         { accountId: "<fiat-account-id>", entryType: "DEBIT", monetaryEntryType: "FIAT", amount: "300.00", currency: "USD", rail: "WIRE" },
         { accountId: "<usdc-account-id>", entryType: "CREDIT", monetaryEntryType: "FIAT", amount: "300.00", currency: "USD", rail: "WIRE" }
       ],
       idempotencyKey: "demo-exec-001",
       intentDescription: "demo cross-border transfer",
       agentId: "agent-demo",
       sessionId: "session-demo-001"
   )
   ```
   Note the returned `workflowPlanId`.

6. **Seed settlement data for one match + one exception.** `reconcile_batch` matches
   `UNMATCHED` on-chain settlements against `PENDING` ones by amount
   (`ReconcileEntriesService.findMatch`, `infrastructure/src/main/kotlin/finance/idem/infrastructure/service/ReconcileEntriesService.kt`).
   To get "two matched, one exception" on screen you need at least three settlement pairs in
   the account's window:
   - Two pairs where the `PENDING` and `UNMATCHED` amounts are equal (or within
     `idem.reconciliation.amount-tolerance-percent`) → these settle and count toward `matched`.
   - One `UNMATCHED` entry with **no** `PENDING` counterpart in range, or one whose amount
     falls outside tolerance → this surfaces in `exceptions` with reason
     `"No matching pending settlement found"`.
   There is currently no API/seed script for settlement rows — they're normally created by the
   chain readers/webhooks (`AlchemyWebhookReceiver`, `QuickNodeWebhookReceiver`) reacting to
   real or simulated on-chain events, or inserted directly for a controlled demo take (see how
   `McpServerIntegrationTest`'s "demo scenario" test seeds `Settlement` rows directly via
   `SettlementRepository` for a deterministic example).

---

## On-camera tool calls

### 0:30 — `reconcile_batch`

```
reconcile_batch(
    accountId: "<usdc-account-id>",
    from: "<window-start-ISO8601>",
    to: "<window-end-ISO8601>"
)
→ { matched: 2, unmatched: 1, exceptions: ["<txHash>: No matching pending settlement found"], settlementIds: [...] }
```

### 1:10 — `rollback_workflow`

```
rollback_workflow(
    workflowPlanId: "<workflowPlanId from setup step 5>",
    reason: "demo: reverting for compliance review",
    agentId: "agent-demo",
    sessionId: "session-demo-001"
)
→ { rollbackId: "...", compensatedSteps: [{ stepOrder: 0, description: "...", compensatingTransactionId: "..." }], status: "ROLLED_BACK" }
```

### 1:45 — `get_agent_audit_log`

```
get_agent_audit_log(
    sessionId: "session-demo-001",
    limit: 50
)
→ { auditEvents: [ { eventType: "...", status: "PENDING"|"COMPLETED", hmacSignature: "...", ... }, ... ], total: N }
```
Point out `hmacSignature` on each event and that the `PENDING` event was written **before**
execution — that's the audit-trail guarantee being narrated at 1:30.

### 2:00 — open-source quick start

Use the exact commands and curl example already in `README.md` "Quick start" /
"Post a transaction" sections — don't re-type a new example, keep the video and the README in
sync.

---

## Recording checklist

- Terminal: dark theme, large font (18px minimum)
- Screen: 1920x1080, no personal info visible
- Audio: voiceover recommended
- Edit: captions for accessibility
- Length: max 3 minutes
- Upload: LinkedIn (native), X (native), YouTube
