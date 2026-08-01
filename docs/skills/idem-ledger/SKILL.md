---
name: idem-ledger
description: Connect to and operate an Idem MCP ledger server — post double-entry transactions, query balances/entries, reconcile on-chain settlements, roll back workflows via saga compensation, and read the HMAC-signed agent audit log. Activate when connecting an AI agent to Idem, or when posting/querying/reconciling/rolling back ledger transactions through MCP.
---

# Idem Ledger Skill

Fast path for operating [Idem](https://github.com/idem-finance/idem) — an event-sourced double-entry
ledger — through its MCP server, without reading the full reference doc first.

## When to Activate

- Connecting an AI agent to an Idem instance over MCP
- Posting a double-entry transaction (fiat or on-chain) as an agent
- Querying account balances, entry history, or account metadata via Idem
- Reconciling on-chain settlements against pending journal lines
- Rolling back a previously executed Idem workflow
- Reading Idem's agent audit trail

## Connecting

Add to your MCP client config (`claude_desktop_config.json`, or `claude mcp add`):

```json
{
  "mcpServers": {
    "idem": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "https://<your-host>/sse"],
      "env": {
        "MCP_HEADER_X_API_KEY": "sk_agent_..."
      }
    }
  }
}
```

The API key needs at least the `AGENTS_EXECUTE` scope; `rollbackWorkflow` additionally needs
`AGENTS_ROLLBACK`, and `getAgentAuditLog` needs `AGENTS_AUDIT_READ` — these are deliberately
separate from `AGENTS_EXECUTE` (an agent that can commit transactions cannot roll them back or
read the audit log unless explicitly granted). For the full walkthrough (ngrok tunneling, creating
an agent-scoped API key, Claude Desktop vs. Claude Code setup) see
[`docs/mcp-server.md`](../../mcp-server.md).

**Installing this skill**: copy this file into your own `.claude/skills/idem-ledger/SKILL.md`
(project-level) or `~/.claude/skills/idem-ledger/SKILL.md` (personal).

## Tool Cheat Sheet

| Tool | Scope | Purpose | Gotcha |
|---|---|---|---|
| `postTransaction` | `AGENTS_EXECUTE` | Post a balanced double-entry transaction | Requires `idempotencyKey` — duplicate calls with the same key return the cached result, not a new transaction. Fails with `PolicyViolationException` if no policy rule permits the debit — a fresh tenant with zero configured rules denies everything by default. |
| `getBalance` | `AGENTS_EXECUTE` | Current or point-in-time account balance | `asOf` is optional and must be ISO-8601 if passed |
| `listEntries` | `AGENTS_EXECUTE` | Paginated journal entry history, newest first | Pagination is cursor-based (`nextCursor` from the previous page), not offset-based |
| `describeAccount` | `AGENTS_EXECUTE` | Account metadata + current balance in one call | Cheapest way to sanity-check an `accountId` before posting against it |
| `reconcileBatch` | `AGENTS_EXECUTE` | Match on-chain settlements to pending journal lines by amount, within a time window | Matching is exact by default; pass `tolerancePercent` to allow a bounded amount difference. `exceptions` reference **settlement** records (settlementId/txHash), not ledger journal lines |
| `rollbackWorkflow` | `AGENTS_ROLLBACK` | Reverse a workflow via compensating transactions (saga pattern), most-recent step first | Separate scope from `AGENTS_EXECUTE` on purpose. Compensating transactions bypass `PolicyGuard` by design — a rollback is corrective, not a new agent-initiated debit |
| `getAgentAuditLog` | `AGENTS_AUDIT_READ` | Retrieve HMAC-signed audit events, filterable by session/time | Event `status` is only `PENDING`/`COMPLETED`/`FAILED` — it does not carry workflow-level outcomes like `ROLLED_BACK`. Use it to confirm a mutating call was recorded, not to read the outcome itself |

## Common Workflows

**Post, then confirm it landed:**
```
postTransaction(entries=[...], idempotencyKey="<uuid>", agentId="...", sessionId="...")
  → workflowPlanId, status="COMMITTED"
getAgentAuditLog(sessionId="...")
  → confirm a COMPLETED event for that workflowPlanId
```

**Reconcile a settlement window, then investigate exceptions:**
```
reconcileBatch(from="...", to="...")
  → { matched, unmatched, exceptions: ["<settlementId/txHash>: <reason>", ...] }
```
`exceptions` reference settlement records, not journal lines — MCP doesn't expose a
settlement-inspection tool. To look one up, use the REST API directly:
`GET /api/v1/settlements?status=UNMATCHED` (requires `TRANSACTIONS_READ` scope).

**Undo a bad transaction:**
```
rollbackWorkflow(workflowPlanId="...", reason="...", agentId="...", sessionId="...")
  → compensatedSteps, status="ROLLED_BACK"   // this IS the confirmation
getAgentAuditLog(sessionId="...")            // optional: confirm a COMPLETED event was recorded
```

## Full Reference

Field-by-field request/response schemas, the SSE session auth-bridge internals, Reactor context
propagation, and known limitations: [`docs/mcp-server.md`](../../mcp-server.md).
