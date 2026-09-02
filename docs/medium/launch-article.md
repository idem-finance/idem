> **Publishing note — delete this block before pasting into Medium.**
> Target publish window: **Aug 20-21, 2026** (2-3 days after the Aug 18 source-available launch — launch day itself is reserved for ProductHunt/HN/Reddit, this piece is for long-term organic discovery + LinkedIn resharing).
> Workflow: publish to personal Medium first → once live, submit to **The Startup** and **Better Programming** publications for extra reach.
> Everything below the `---` is the article body, ready to paste as-is.

---

# How I built a source-available ledger for stablecoin cross-border payments

### A Brazilian fintech engineer's attempt to fix the accounting gap nobody wanted to own

A payment service provider in São Paulo processes a USDC transfer on Base. The on-chain transfer confirms in three seconds — final, irreversible, sitting right there on the block explorer. Their internal ledger still says `PENDING` twenty minutes later, because nobody wired up the confirmation step. Their ops team has a browser tab open to Etherscan, refreshing it by hand. Their compliance officer is tracking the day's settlements in a spreadsheet, because the ledger and the blockchain don't agree with each other and someone has to be the source of truth.

This isn't a rare edge case. After twelve years building fintech infrastructure in the JVM world — wallet integrations, KYC pipelines, event-sourced ledgers processing well over a hundred million requests an hour at scale — I'd argue it's the *default* state of most stablecoin payment operations today. Every real cross-border stablecoin flow follows the same shape: fiat comes in, gets converted on-chain, and comes back out as fiat on the other side. The "stablecoin sandwich." And at every seam in that sandwich, someone is reconciling two systems that were never designed to talk to each other.

Here's why the gap exists. A fiat ledger's mental model is `amount + currency + rail` — a PIX transfer, a wire, an ACH batch. It has no concept of a transaction hash or a block number, because it was built for a world where settlement finality comes from a bank, not a chain. A crypto wallet's mental model is the inverse: it knows about transaction hashes and confirmations, but it has never heard of double-entry accounting, debits, or credits — it just has a balance. Neither side was built to understand the other, so the industry's default answer has been glue code: a script that polls a chain, a webhook nobody monitors after the third week, a human closing the gap with a spreadsheet.

I've spent twelve years on the wallet-integration and event-sourcing side of this exact problem, first at a stablecoin-focused fintech doing Fireblocks/MPC wallet work, and before that building ledger and reconciliation infrastructure at scale across a few LatAm payment companies. Every time, the on-chain-to-ledger seam was the part nobody had budgeted for, and it was always the part that broke first when volume showed up. That gap is exactly what I set out to close.

---

## What I built

**Idem** is a source-available, event-sourced double-entry ledger built to model fiat and on-chain money movement in the *same* transaction, natively — not as two systems bolted together after the fact.

The central architectural bet is a single sealed class:

```kotlin
sealed class MonetaryEntry
  ├── FiatEntry(amount, currency, bankReference?, rail)
  └── OnChainEntry(amount, token, chainId, txHash, blockNumber,
                    walletAddress, tokenContract, fromAddress?, travelRuleData?)
```

Every journal line in Idem is one of these two things, and only these two things. A PIX transfer of 1,000 BRL is a `FiatEntry`. A 180 USDC transfer on Base is an `OnChainEntry`. Both flow through the identical `JournalLine → Transaction` path, and both are checked against the same invariant: debits equal credits, enforced once, in the domain layer, with no bypass.

Why a sealed class instead of one entry type with a pile of nullable fields? Because Kotlin's compiler turns every `when` expression over a `MonetaryEntry` into an exhaustiveness check. If I add a third kind of entry later — say, a CBDC rail — every place in the codebase that pattern-matches on `MonetaryEntry` fails to compile until it explicitly handles the new case. On a ledger, "the compiler won't let you forget a branch" is worth more than almost any other line of defense you can add, because a forgotten branch in a ledger doesn't throw an exception in production — it silently posts a transaction that doesn't balance.

The on-chain side ingests automatically. Here's the flow for a single transfer:

```
Wallet address registered in watched_addresses
        │
        ▼
Alchemy (EVM) or QuickNode (Solana) webhook fires on a matching Transfer event
        │
        ▼
OnChainEntry auto-posted, idempotency-keyed by chainId:txHash
        │
        ▼
BasicReconciliationService matches it against a PENDING Settlement row
        │
        ▼
Settlement marked SETTLED, on-chain proof attached
        │
        ▼
Webhook fires to the customer via the transactional outbox poller
```

No polling loop standing between the transfer and the ledger update, no ops person tabbing over to a block explorer. Tron, which has no webhook infrastructure worth trusting, still gets polled via Tronscan on a schedule — but EVM and Solana are event-driven end to end, with the readers themselves demoted to startup-recovery fallbacks.

---

## The agentic engine

"Agentic" gets used loosely enough that it's worth being precise about what it means in Idem: it is not a chatbot sitting on top of a ledger, answering questions about your balance. It's `PolicyGuard`, `AgentAuditEvent`, and a workflow/rollback layer built as first-class domain entities — not middleware wrapped around an API that was never designed to be called by something autonomous.

Every agent-originated mutation passes through `PolicyGuard` before it touches the ledger — a stateless rules engine evaluating things like per-session debit caps, forbidden account pairs, and human-approval thresholds, all at once, reporting every violation rather than just the first one. If it fails, nothing is written. Not the transaction, not the audit entry, not the webhook.

The property I care about most is this: the HMAC-signed `AgentAuditEvent` is written to an append-only table *before* the transaction commits — not after. If the process crashes in the narrow window between the audit write and the commit, you still have a signed record that an agent attempted a specific action. That ordering is what makes the trail auditable instead of merely logged — a log is best-effort; an audit trail written before the fact survives the failure it's supposed to explain. And if a multi-step agent workflow fails partway through, `RollbackService` reverses the completed steps with compensating entries — a saga, not a two-phase commit — so the books never sit in a half-committed state waiting for a human to sort it out by hand.

The clearest way to see this working is the MCP demo: Claude Code driving `reconcile_batch`, `rollback_workflow`, and `get_agent_audit_log` end-to-end against a live Idem instance. [Watch it here](https://youtu.be/My_QOeOsFqg).

---

## Design decisions I'd make differently

Being honest about this is more useful than another highlight reel, so here's what I'd change if I started over.

**I built polling chain readers before webhook ingestion, and it cost me about two weeks.** The first version of the EVM and Solana readers walked blocks on a schedule, diffing against a checkpoint. It worked, but "worked" meant a lag between on-chain confirmation and ledger state that defeated half the point of the project. Switching to Alchemy Notify and QuickNode Streams as the primary path — with the original readers demoted to startup-recovery fallbacks — was the right call, but it was a rebuild, not a refactor. I should have looked harder at event-driven chain infrastructure before writing the first polling loop.

**The settlement-matching model took several iterations to get right.** My first pass keyed reconciliation candidates loosely enough that two different customer transactions could plausibly match the same on-chain transfer under load. It took working through the failure cases by hand to land on the current model — a `Settlement` row that must match on tenant, account, token, chain, wallet address, *and* amount within a fixed window before it's eligible to settle. The distinction that actually mattered wasn't obvious on day one: matching has to be anchored to the specific `AccountId`, not just "some account this tenant owns," or you get false-positive settlements between accounts that happen to share a currency.

**I should have locked the license before writing three months of code, not after.** FSL turned out to be the right choice — free to self-host and modify, protected against someone repackaging it as a competing managed service, converting to Apache 2.0 automatically after two years. But I made that call after the fact, once there was already a real codebase and a real decision to unwind if I'd chosen wrong. A licensing decision this consequential belongs on day one, before a single commit, not retrofitted onto months of work.

---

## What's next

The source-available engine is live today: the ledger core, chain readers for EVM/Solana/Tron, API-key auth backed by PostgreSQL row-level security, reconciliation, the agentic engine, and the full MCP server — all under FSL, all yours to self-host.

Next up is a managed Cloud tier — multi-tenant, hosted on GCP São Paulo, for teams that want Idem without running their own PostgreSQL and Redis at 3am. Same core engine, same API, just infrastructure you don't manage. After that, an Enterprise tier: an AML rule engine, BACEN-format reporting, human-in-the-loop workflow checkpoints, and multi-tenant sub-accounts, for institutions that need all of the above running in their own VPC or a dedicated deployment. The source-available engine underneath doesn't change between tiers — what changes is who operates it and which compliance surface is switched on.

If you're building cross-border payment infrastructure and you have a real reconciliation problem — not a hypothetical one — I want to talk to you. I'm looking for early pilot partners.

- GitHub: [github.com/idem-finance/idem](https://github.com/idem-finance/idem)
- Website: [idem.finance](https://idem.finance)
- X: [@idem_finance](https://x.com/idem_finance)
- LinkedIn: [idem-finance](https://www.linkedin.com/company/idem-finance)

---

**Tags:** fintech, stablecoin, source-available, kotlin, blockchain, payments
