# Idem Agentic Capabilities — Explained for Everyone

> This document explains what Idem's agentic layer is, how it works, why it exists, and what problems it solves. It is written for a non-technical reader. Engineers who want the implementation details should read `CLAUDE.md` and the `core/agentic` source.

---

## The Problem This Solves

Modern finance teams are starting to use AI to automate tasks like reconciling payments, moving balances between accounts, and flagging suspicious transfers. The promise is obvious: instead of a human manually reviewing 10,000 USDC transactions on a Monday morning, an AI agent does it overnight and surfaces only the exceptions.

But there is a critical gap: **no existing ledger system was built to be safely operated by an AI.** When you let an AI agent write to a ledger without guardrails, you get three dangerous scenarios:

1. **The agent moves too much money.** No human reviewed a $2M transfer that was supposed to be $20K.
2. **The agent does something it was never supposed to do.** Like debiting a reserve account that is legally segregated.
3. **The agent makes a mistake partway through a multi-step workflow.** Half the entries are committed; the other half failed. The books are now wrong.
4. **Nobody can tell what the agent did or why.** The AI left no audit trail a compliance officer can read.

Idem's agentic layer exists to solve all four problems — simultaneously, before any of them can happen.

---

## What "Agentic" Actually Means Here

"Agentic" simply means **the ledger is designed to be operated by AI agents, not just humans calling an API.**

In practice this means:
- An AI agent can call Idem to post transactions, query balances, and reconcile entries — just like a human using a dashboard.
- But unlike a dashboard, the agent never sleeps. It can process thousands of operations per hour.
- And unlike trusting the AI blindly, Idem enforces a set of rules — called **policies** — that the agent cannot bypass, no matter what it tries to do.

Think of it like giving a very capable intern access to the company bank account — but with a supervisor standing at the door who checks every transaction before it goes through, writes down everything the intern does, and knows how to undo the last 10 steps if something goes wrong.

---

## The Four Pillars of Idem's Agentic Layer

### 1. Policy Guard — The Gatekeeper

Before any agent-originated transaction is committed to the ledger, it passes through **PolicyGuard**. This is a pure rules engine — no database, no network calls, just logic. It takes two inputs:

- **Who is the agent?** (identity, session, what workflow it's executing)
- **What does the agent want to do?** (the specific debits and credits it intends to post)

Then it evaluates a set of rules configured per customer and returns one of two answers: **Approved** or **Denied (with a list of exactly which rules were violated)**.

#### The Rules Available Today

| Rule | What it does | Example |
|------|-------------|---------|
| **MaxDebitPerSession** | Caps how much an agent can debit in a single session | Agent cannot move more than $50,000 in one login session |
| **MaxDebitPerHour** | Caps debits across a rolling hourly window | Even with multiple sessions, total debits cannot exceed $100,000/hour |
| **ForbiddenAccountPair** | Blocks a specific debit→credit combination | Agent can never move money from Reserve Account A to External Account B together |
| **RequireHumanApprovalAbove** | Pauses the workflow and requires a human to approve | Any single debit above $10,000 needs a human to sign off before it executes |
| **AllowedTokens** | Restricts which stablecoins the agent may touch | This agent may only use USDC. Never USDT. |
| **AllowedChains** | Restricts which blockchains the agent may write entries for | This agent is only allowed to post Ethereum transactions. Not Solana, not Tron. |

Rules are **all evaluated at once** — if an intent violates three rules simultaneously, all three violations are reported. The agent (or the system) gets a complete picture of what went wrong, not just the first failure.

#### How PolicyGuard Knows About History

Some rules require knowing what the agent has already done. For example, to enforce "no more than $50K debited per hour," the PolicyGuard needs to know how much was already debited in the past hour. The application layer queries historical journal data and hands that total to PolicyGuard before evaluation. PolicyGuard itself has no database — this keeps it fast, testable, and impossible to bypass via a database trick.

---

### 2. Agent Identity — Who Is Asking?

Every agent call carries an **AgentContext**, a small envelope that identifies:

- **agentId** — which AI agent this is (e.g., "reconciliation-bot-v2")
- **sessionId** — which session it's currently in (one agent can run many sessions)
- **workflowPlanId** — which multi-step workflow this action belongs to (optional — single-step calls don't need one)
- **intent** — a human-readable description of what the agent is trying to do ("reconcile overnight USDC settlements for tenant ACME")

This context travels with every single operation. It is written into the audit log before the transaction executes. If something goes wrong six months later, a compliance officer can open the audit trail and read exactly which agent, in which session, running which workflow, attempted which action — and whether it was approved or denied.

---

### 3. Agent Audit Trail — The Immutable Record *(planned — M3-4)*

Every agent action produces an **AgentAuditEvent** that is:

- Written **before** execution — not after. If the system crashes mid-execution, the intent is still recorded.
- **HMAC-signed** with the tenant's secret key — meaning it cannot be modified without invalidating the signature.
- Stored in an append-only table — no UPDATE or DELETE is ever permitted, even by a database administrator.

This gives customers something they cannot get anywhere else: a tamper-evident, court-admissible record of every AI action taken on their ledger. For regulated fintechs, this is not a nice-to-have — it is a compliance requirement.

---

### 4. Workflow Orchestration and Rollback — Safe Multi-Step Execution *(planned — M3-4)*

The hardest problem in agentic finance is what happens when a multi-step workflow fails in the middle.

Imagine an AI agent is reconciling 500 transactions:
- Steps 1–300: succeed. Entries committed.
- Step 301: fails. External API is down.

Without rollback, the ledger is now partially updated. The books are wrong. A human has to manually find which of the 500 steps completed and reverse them by hand.

Idem's **WorkflowOrchestrator** and **RollbackService** solve this using a pattern called the **saga pattern** (also called compensating transactions):

- Each step in a workflow is tracked.
- On failure, the system automatically posts **compensating entries** that reverse the committed steps, restoring the ledger to a consistent state.
- This never uses database-level 2PC (two-phase commit) — it uses Idem's own double-entry logic. A compensating debit reverses a credit. A compensating credit reverses a debit. The math always balances.

The customer never has to manually clean up a partially-executed agent workflow.

---

## How the MCP Server Connects AI Agents to Idem

**MCP** (Model Context Protocol) is a standard that allows AI assistants like Claude to call external tools. Idem's MCP server is the bridge between an AI agent and the ledger.

Instead of writing custom API integrations, a customer can plug Idem directly into their AI environment. The agent sees these tools:

| Tool | What it does |
|------|-------------|
| `post_transaction` | Post a double-entry transaction (fiat or on-chain) |
| `get_balance` | Query an account balance at any point in time |
| `list_entries` | List journal entries for an account with date filters |
| `rollback_workflow` | Cancel a multi-step workflow and reverse committed steps |
| `describe_account` | Get metadata about an account |
| `reconcile_batch` | Match external entries against ledger records |
| `get_agent_audit_log` | Pull the full audit trail for a session or time window |

Every call to `post_transaction` via the MCP server goes through PolicyGuard before anything is written. The agent cannot bypass this — it is enforced at the application layer, not by telling the AI to "be careful."

---

## What Happens Step by Step (The Happy Path)

Here is the complete flow for an agent posting a transaction through Idem:

```
1. AI agent (e.g., Claude Code, a custom bot) calls post_transaction via MCP
   └─ Sends: AgentContext (who I am) + LedgerIntent (what I want to do)

2. Application layer computes historical totals
   └─ Queries journal for agent's prior debits this session and this hour

3. PolicyGuard evaluates all configured rules
   ├─ APPROVED → proceed
   └─ DENIED   → return list of violations; execution stops; nothing is written

4. AgentAuditEvent is written to the append-only audit table (BEFORE execution)
   └─ HMAC-signed with tenant key; records intent, agent identity, timestamp

5. TransactionEngine commits the transaction atomically
   ├─ Ledger entries (journal_lines) written
   ├─ Audit entry written (same database transaction)
   └─ Webhook outbox event queued (same database transaction)

6. Webhook is delivered to the customer's endpoint (within ~5 seconds)
   └─ WebhookOutboxPoller picks it up; retries with exponential backoff if delivery fails

7. If the workflow has more steps → repeat from step 1 for the next step
   If any step fails → RollbackService posts compensating entries for all completed steps
```

Everything in steps 4–6 is a **single database commit**. Either all three writes succeed together, or none of them do. There is no state where a transaction was committed but not audited.

---

## What Happens When an Agent Tries to Break the Rules (The Denial Path)

```
1. AI agent calls post_transaction
   └─ Intent: debit $75,000 from account A (limit is $50,000/hour)
      Intent: use USDT (only USDC is allowed)

2. PolicyGuard evaluates — finds TWO violations:
   └─ MaxDebitPerHour: running total $75,000 exceeds limit $50,000
   └─ AllowedTokens: USDT is not in allowed set {USDC}

3. PolicyEvaluationResult.Denied is returned
   └─ Both violations are reported — not just the first one

4. NOTHING is written to the ledger
   └─ No transaction, no audit entry, no webhook

5. The denial itself IS recorded (as a failed attempt)
   └─ Agent's session history is updated; the denied amount counts toward future checks
```

The agent receives a structured error response listing every rule it violated. A well-designed AI can read this, understand what it cannot do, and either stop or escalate to a human.

---

## Customer Benefits by Persona

### For a Fintech CTO or Architect

- **Drop-in agentic safety.** You do not need to build a rules engine for your AI agents. Idem gives you one that is tested, auditable, and runs before every mutation.
- **No "magic" required.** PolicyGuard is pure Kotlin — no ML, no black box. Every decision is traceable to a specific rule with a specific threshold you configured.
- **The MCP server means zero integration work.** If your AI environment supports MCP (Claude, Cursor, any MCP-compatible agent framework), you plug in Idem and your agent has access to the ledger in minutes.

### For a Compliance Officer or Risk Manager

- **Every agent action has a reason, a timestamp, and a signature.** The audit log cannot be altered. If the regulator asks "what did your AI do on March 14th at 2am?", the answer is a signed, queryable record.
- **Human approval gates are first-class.** You do not need to add approval logic to your AI code. You configure a rule: "any debit above $10,000 requires human approval." Idem enforces it on every call.
- **No agent can exceed its scope.** Agent API keys (`sk_agent_` prefix) are structurally restricted — they cannot be granted compliance export or admin scopes. PolicyGuard adds a second layer of control on top.

### For a Business Owner or CEO

- **Your AI agents run 24/7 without risk of catastrophic mistakes.** Spending limits, forbidden operations, and human approval thresholds are enforced mechanically — not by telling the AI to "use good judgment."
- **If something goes wrong, it can be undone.** The rollback system reverses partial workflows automatically. You do not wake up to a Monday morning phone call explaining that the AI got stuck halfway through a batch reconciliation and now the books are wrong.
- **You retain full visibility.** Every agent action flows through your audit trail. You can always answer "what has our AI been doing to our ledger?"

---

## Use Cases

### Use Case 1 — Overnight Stablecoin Reconciliation

**The problem:** A cross-border PSP settles 2,000 USDC transactions every night. Someone has to match each blockchain transaction to an internal ledger entry, flag the ones that don't match, and mark the confirmed ones as settled. Today this takes a human analyst 4 hours every morning.

**With Idem agentic:** An AI agent runs at midnight. It calls `reconcile_batch` with the day's on-chain transfers. Idem matches each blockchain record to a ledger entry by amount, sender address, and transaction hash. Confirmed matches are automatically marked settled. Unmatched entries are queued in an exception report. By 12:15am, the work is done. The human reviews only the exceptions — usually 10–20 out of 2,000.

**Guardrails active:** AllowedTokens (USDC only), AllowedChains (Ethereum + Base), MaxDebitPerHour (prevents accidental mass settlement beyond threshold).

---

### Use Case 2 — Agent-Driven Batch Payment Posting

**The problem:** At the end of each day, a stablecoin offramp needs to post hundreds of payout entries to the ledger — debiting the settlement pool account and crediting individual customer accounts. Today this is a manual job or a fragile script with no rollback.

**With Idem agentic:** A workflow agent runs nightly, posting each payout as a double-entry transaction. PolicyGuard enforces the per-session debit limit. If a payout exceeds the human-approval threshold, the workflow pauses and routes the entry to a human reviewer queue instead of executing it. If the batch fails partway through (network outage, upstream error), RollbackService automatically posts compensating entries to reverse the partial work. Tomorrow morning, the books are in a consistent state — either all committed or none committed.

**Guardrails active:** MaxDebitPerSession, RequireHumanApprovalAbove, ForbiddenAccountPair (prevents accidental self-transfers).

---

### Use Case 3 — Claude Code as a Ledger Copilot (The Demo)

**The problem:** A developer wants to use Claude Code to interact with their ledger for exploratory queries, test data setup, and one-off corrections — without writing raw API calls.

**With Idem MCP:** The developer adds the Idem MCP server to their Claude Code session. They type natural-language requests: "Show me the last 10 entries for account ACC-001" or "Post a test USDC transaction of $100 between these two accounts." Claude calls the MCP tools directly. PolicyGuard runs on every mutation. The developer gets a typed, policy-safe ledger interface in their AI coding assistant.

This is also the primary demo that drives source-available adoption: a 2-minute recording showing Claude Code reconciling a batch, rolling back a failure, and producing an audit report — all via MCP.

---

### Use Case 4 — Multi-Step Arbitrage or FX Settlement Workflow

**The problem:** A LatAm PSP executes a 4-step workflow: (1) receive USDC on Ethereum, (2) post fiat BRL credit to customer, (3) debit FX conversion fee, (4) move net amount to settlement pool. If step 3 fails, steps 1 and 2 are already committed and the books are wrong.

**With Idem agentic:** The WorkflowOrchestrator tracks each step under a single `workflowPlanId`. On step 3 failure, RollbackService posts compensating journal entries for steps 1 and 2 (debit what was credited, credit what was debited). The net effect: the ledger returns to its pre-workflow state. Every step — including the failure and the rollback — is in the audit trail.

**Guardrails active:** AllowedChains, AllowedTokens, MaxDebitPerSession, ForbiddenAccountPair (prevents FX fee from accidentally crediting the wrong account).

---

## What Is Not the Agentic Layer's Job

Knowing the boundaries is as important as knowing the capabilities:

- **Idem does not move money.** It records money movement. The AI agent tells Idem "this transfer happened" — Idem does not instruct a bank to wire funds.
- **PolicyGuard does not make business decisions.** It enforces the rules you configure. You decide what the rules are. PolicyGuard just ensures they are followed mechanically, every time.
- **The audit trail does not replace your compliance process.** It is evidence. What you do with that evidence — reporting to BACEN, filing a SAR, responding to a LGPD audit request — is handled by the compliance layer (Travel Rule, LGPD, AML), not by the agentic layer itself.

---

## Component Map

```
┌────────────────────────────────────────────────────────────┐
│                    AI Agent (external)                      │
│    Claude Code / custom bot / workflow scheduler            │
└───────────────────────────┬────────────────────────────────┘
                            │ calls tools via
                            ▼
┌────────────────────────────────────────────────────────────┐
│                    MCP Server  (mcp module)                  │
│  post_transaction · get_balance · reconcile_batch           │
│  rollback_workflow · list_entries · get_agent_audit_log     │
└───────────────────────────┬────────────────────────────────┘
                            │ delegates to
                            ▼
┌────────────────────────────────────────────────────────────┐
│              Application Layer  (application module)        │
│                                                             │
│  1. Look up agent's history (prior debits this hour/session)│
│  2. Build LedgerIntent from the request                     │
│  3. Call PolicyGuard.evaluate(agentContext, intent, rules)  │
│     ├─ Denied  → return error; write denial to audit log    │
│     └─ Approved → continue                                  │
│  4. Write AgentAuditEvent (BEFORE execution)                │
│  5. Call TransactionEngine.commit(transaction)              │
│  6. Write to webhook_outbox (same tx as steps 4+5)          │
└───────────────────────────┬────────────────────────────────┘
                            │
              ┌─────────────┼──────────────┐
              ▼             ▼              ▼
┌─────────────────┐ ┌────────────┐ ┌────────────────────────┐
│  PolicyGuard    │ │ Audit Log  │ │  TransactionEngine      │
│  (core module)  │ │ (PostgreSQL│ │  (core module)          │
│                 │ │  append-   │ │                         │
│  Evaluates all  │ │  only,     │ │  Enforces double-entry  │
│  PolicyRules    │ │  HMAC-     │ │  invariant; atomic      │
│  against the    │ │  signed)   │ │  commit to journal_lines│
│  LedgerIntent   │ └────────────┘ └────────────────────────┘
│  Returns:       │
│  Approved|Denied│
└─────────────────┘

             On workflow failure:
             ▼
┌────────────────────────────────────────────────────────────┐
│               RollbackService  (application module)         │
│  Posts compensating journal entries for each completed step │
│  Restores ledger to pre-workflow state                      │
│  Records rollback in audit trail                            │
└────────────────────────────────────────────────────────────┘
```

---

## Current Status

| Component | Status | Notes |
|-----------|--------|-------|
| AgentContext | ✅ Built | Identity envelope for every agent call; includes `apiKeyPrefix` for audit trail |
| LedgerIntent / LedgerIntentLine | ✅ Built | Describes what the agent wants to do |
| PolicyRule (all 6 rules) | ✅ Built | MaxDebit, ForbiddenPair, HumanApproval, Tokens, Chains |
| PolicyGuard | ✅ Built | Stateless evaluator, 100% unit tested |
| PolicyEvaluationResult | ✅ Built | Approved / Denied with full violation list |
| Agent API key (`sk_agent_` prefix) | ✅ Built | Restricted scopes at key level |
| ExecuteWorkflowUseCase | ✅ Built | Application-layer entry point for agent transactions |
| MCP server — `post_transaction` | ✅ Built | PolicyGuard fires; policyRules wired empty until #200 |
| MCP server — `get_balance` | ✅ Built | Point-in-time balance query |
| MCP server — `list_entries` | ✅ Built | Keyset-paginated entry timeline |
| MCP server — `describe_account` | ✅ Built | Account metadata + current balance |
| MCP SSE auth bridge | ✅ Built | `McpSseAuthBridgeFilter` + `McpSseSessionAuthStore` |
| Reactor context propagation | ✅ Built | `McpReactorSecurityConfig` — auth visible across Reactor threads |
| PolicyRepository | Planned #200 | Per-tenant/per-agent rule storage; wires real rules into `post_transaction` |
| AgentAuditEvent (HMAC-signed) | Planned M3-4 | Written before execution; append-only |
| WorkflowOrchestrator | Planned M3-4 | Multi-step execution tracking with `workflowPlanId` |
| RollbackService | Planned M3-4 | Compensating transactions (saga pattern, no 2PC) |
| RollbackWorkflowUseCase | Planned M3-4 | Application-layer rollback entry point |
| MCP server — `rollback_workflow` | Planned M3-4 | Depends on RollbackService |
| MCP server — `reconcile_batch` | Planned M3-4 | MCP wiring for ReconcileEntriesUseCase |
| MCP server — `get_agent_audit_log` | Planned M3-4 | Depends on AgentAuditEvent (#160) |

The policy enforcement layer, ExecuteWorkflowUseCase, and first four MCP tools are complete. Next: PolicyRepository (#200), then AgentAuditEvent, WorkflowOrchestrator, and RollbackService to unlock the remaining tools.
