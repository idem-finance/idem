# Idem — Core Domain Model

> Reference specification for the `core` module.
> Every class here is pure Kotlin — zero Spring, JPA, or framework imports.

---

## Ledger primer — the four rules everything is built on

1. **Every transaction touches ≥ 2 accounts** (double-entry)
2. **Total debits == total credits** in every transaction (the balanced invariant)
3. **An account's normal balance determines what a debit/credit means to its balance**
   - ASSET / EXPENSE → normal balance is DEBIT (debit increases the balance)
   - LIABILITY / EQUITY / REVENUE → normal balance is CREDIT (credit increases the balance)
4. **A journal line's monetary value can be either fiat or on-chain** — same invariant, different
   representation. A USDC transfer on Base and a PIX transfer in BRL are both first-class entries.

---

## Audit field convention

All domain entities carry `createdAt: Instant` and `createdBy: String`. `createdBy` is the actor
string — API key prefix (e.g. `sk_live_xxxx`), agent ID, or `"system"` for seeded data.

`updatedAt: Instant?` and `updatedBy: String?` are present **only on mutable entities**:

| Entity | updatedAt / updatedBy | Rationale |
|---|---|---|
| `Account` | ✅ nullable | Name and description can be changed |
| `JournalLine` | — | Immutable — part of a committed transaction |
| `Transaction` | — | Status changes via domain events, not field mutations |

---

## Layer 1 — Primitives

**Package:** `finance.idem.core`

Strongly-typed IDs wrapping `UUID` via `@JvmInline value class`. Prevents primitive obsession —
the compiler can't let you pass a `TransactionId` where an `AccountId` is expected. Zero runtime
allocation overhead.

| Class | Wraps | Role |
|---|---|---|
| `AccountId` | `UUID` | Identity of one ledger account |
| `TransactionId` | `UUID` | Identity of one balanced posting |
| `TenantId` | `UUID` | Multi-tenant isolation key — injected from API key at gateway |
| `WorkflowPlanId` | `UUID` | Identity of an agentic workflow (rollback target) |
| `ApiKeyId` | `UUID` | Identity of one issued API key (`finance.idem.core.security`) |

**`MonetaryAmount`** wraps `BigDecimal`, enforces scale ≤ 18 (covers any stablecoin mantissa),
and exposes `plus`, `minus`, `isZero`. All monetary arithmetic goes through here — once,
centrally, with consistent scale rules. Equality is numeric (`0 == 0.00`), not scale-sensitive.

**Enums:**

| Enum | Values | Role |
|---|---|---|
| `FiatCurrency` | BRL, USD, MXN, EUR | ISO 4217 subset for supported fiat rails |
| `StablecoinToken` | USDC, USDT, BRZ, PYUSD | On-chain tokens Idem reconciles |
| `ChainId` | EVM, SOLANA, TRON | Which blockchain network |
| `PaymentRail` | ACH, WIRE, PIX, SWIFT, SEPA | Which fiat clearing network |
| `EntryType` | DEBIT, CREDIT | The two sides of double-entry |

**Why WIRE is separate from SWIFT:** WIRE (Fedwire/CHIPS) is US domestic real-time gross
settlement — same-day finality, high value, used by institutional counterparties and treasury
ops. SWIFT is the messaging network for international transfers through correspondent banks
(1–2 day settlement). Different finality, fees, reversibility, and regulatory treatment — the
reconciliation engine must distinguish them.

---

## Layer 2 — MonetaryEntry

**Package:** `finance.idem.core.monetary`

The central design bet. A `JournalLine` in a cross-border stablecoin ledger must represent two
fundamentally different things:

- A PIX transfer of BRL 1,000 → needs `currency`, `rail`, optionally `bankReference`
- A USDC transfer of 180.00 on Base → needs `token`, `chainId`, `txHash`, `blockNumber`,
  `walletAddress`, `tokenContract`

Sealed class rather than a nullable field soup:

```
sealed class MonetaryEntry
  ├── FiatEntry(amount: MonetaryAmount, currency: FiatCurrency, bankReference: String?, rail: PaymentRail)
  └── OnChainEntry(amount: MonetaryAmount, token: StablecoinToken, chainId: ChainId, txHash: String,
                   blockNumber: Long, walletAddress: String, tokenContract: String,
                   fromAddress: String? = null)
```

The compiler forces every `when` expression to handle both cases — no forgotten branch, no
runtime cast. The chain reader produces `OnChainEntry`; a PIX webhook produces `FiatEntry`.
Both flow through the same `JournalLine → Transaction` path.

`fromAddress` is the on-chain sender, populated by the EVM and Tron chain readers and the
Alchemy webhook receiver (from the ERC-20 `Transfer` event's `topics[1]` / `from_address`);
`null` for Solana, which doesn't parse the sender from raw JSON-RPC yet. `BasicReconciliationService`
uses it as a preferred, non-exclusive match signal — see `docs/reconciliation.md`.

**Invariants enforced in `init` (throws `LedgerInvariantViolation`):**
- `amount > 0` — zero or negative amounts are a programming error
- `txHash.isNotBlank()` for `OnChainEntry`
- `walletAddress.isNotBlank()` and `tokenContract.isNotBlank()` for `OnChainEntry`

---

## Layer 3 — Account

**Package:** `finance.idem.core.ledger`

A named slot in the chart of accounts. **No balance field** — balance is always derived by
summing journal lines. The account record is metadata; journal lines are the source of truth.

```
Account(id: AccountId, tenantId: TenantId, name: String, description: String? = null,
        currency: FiatCurrency, type: AccountType, normalBalance: EntryType,
        createdAt: Instant, createdBy: String,
        updatedAt: Instant? = null, updatedBy: String? = null)
```

`createdBy` is the actor string — API key prefix (e.g. `sk_live_xxxx`) or `"system"`.
`updatedAt`/`updatedBy` are nullable; set only when the account is mutated after creation.

**`AccountType` and what each means for a stablecoin PSP:**

| Type | Normal balance | Examples |
|---|---|---|
| `ASSET` | DEBIT | Nostro accounts, customer wallet balances, USDC holdings |
| `LIABILITY` | CREDIT | Customer fiat obligations (money owed to customers) |
| `EQUITY` | CREDIT | Owner's capital |
| `REVENUE` | CREDIT | Fee income, FX spread |
| `EXPENSE` | DEBIT | Network gas fees, correspondent bank charges |

`normalBalance` is derived from `AccountType` via companion factory — callers never set it
directly. Prevents an ASSET account from being constructed with CREDIT as its normal balance.

---

## Layer 3 — JournalLine

**Package:** `finance.idem.core.ledger`

One side of a double-entry posting. Never exists alone — always belongs to a `Transaction`
with at least one counterpart.

```
JournalLine(id: UUID, transactionId: TransactionId, accountId: AccountId,
            entryType: EntryType, monetaryEntry: MonetaryEntry, description: String? = null,
            createdAt: Instant, createdBy: String)
```

No `updatedAt`/`updatedBy` — `JournalLine` is immutable once part of a committed `Transaction`.

**Concrete example — USDC → BRL offramp:**

Customer sends 180 USDC on Base, receives BRL 1,000 via PIX:

```
Transaction "offramp-001"
  Line 1: DEBIT   Nostro-USDC-Base      OnChainEntry(180 USDC, EVM, txHash=0xabc…)
  Line 2: CREDIT  Customer-USDC-Bridge  OnChainEntry(180 USDC, EVM, txHash=0xabc…)
  Line 3: DEBIT   Customer-BRL-Payable  FiatEntry(1000 BRL, PIX)
  Line 4: CREDIT  Nostro-BRL-PIX        FiatEntry(1000 BRL, PIX)

Debits: USDC 180 + BRL 1000
Credits: USDC 180 + BRL 1000 ✓  (balanced per currency)
```

---

## Layer 4 — Transaction aggregate

**Package:** `finance.idem.core.ledger`

The aggregate root. Unit of atomicity — all lines commit together or not at all. Owns the
double-entry invariant.

```
Transaction(id: TransactionId, tenantId: TenantId, idempotencyKey: String,
            lines: List<JournalLine>, status: TransactionStatus,
            agentContext: AgentContext? = null, metadata: Map<String, String> = emptyMap(),
            occurredAt: Instant, createdAt: Instant, createdBy: String)
```

`occurredAt` ≠ `createdAt`: `occurredAt` is the business event time (can be backdated for reconciliation); `createdAt` is when the system record was persisted. No `updatedAt`/`updatedBy` — status transitions are tracked via the `TransactionCommitted` domain event.

**`TransactionStatus`:** `PENDING` → `COMMITTED` | `ROLLED_BACK`

**`Transaction.create()` is the only valid entry point.** The primary constructor is `internal`.
`create()` builds the instance, calls `validate()`, and throws `LedgerInvariantViolation` if
any invariant is violated — before any persistence happens.

**Three invariants in `validate()`:**
1. `lines.size >= 2` — no single-sided entries
2. Per-currency `sum(DEBIT amounts) == sum(CREDIT amounts)` — checked independently per
   currency (BRL lines and USDC lines each balance within themselves)
3. All lines reference the same `tenantId` context — cross-tenant lines are a hard error

**`TransactionCommitted`** — immutable `data class` domain event emitted after commit.
Plain Kotlin, no Spring `ApplicationEvent`. The application layer reads it to trigger audit
log and webhook outbox writes in the same `@Transactional`.

---

## Layer 5 — Repository interfaces

**Package:** `finance.idem.core.ledger`

Port definitions only. No `@Repository`, no Spring Data, no `JpaRepository` — those belong in
`infrastructure`. The application layer depends on these interfaces, not on any adapter.

```kotlin
interface AccountRepository {
    fun findById(id: AccountId, tenantId: TenantId): Account?
    fun save(account: Account): Account
    fun findAllByTenantId(tenantId: TenantId): List<Account>
    fun existsById(id: AccountId, tenantId: TenantId): Boolean
}

interface TransactionRepository {
    fun findById(id: TransactionId, tenantId: TenantId): Transaction?
    fun save(transaction: Transaction): Transaction
    fun findByIdempotencyKey(key: String, tenantId: TenantId): Transaction?
    fun findByAccountId(accountId: AccountId, tenantId: TenantId): List<Transaction>
}

interface JournalLineRepository {
    fun findByAccountId(
        accountId: AccountId,
        tenantId: TenantId,
        from: Instant?,
        to: Instant?,
        afterCreatedAt: Instant?,
        afterId: UUID?,
        limit: Int,
    ): List<JournalLine>
}
```

`tenantId` is always an explicit parameter — never assumed from context. The infrastructure
adapter activates PostgreSQL RLS via `SET LOCAL app.tenant_id`, but the interface signature
makes the multi-tenancy contract visible to every caller.

`JournalLineRepository.findByAccountId` is keyset-paginated — `afterCreatedAt`/`afterId`
anchor a page to the last row of the previous page, ordered `createdAt DESC, id DESC` —
rather than offset-based. On an append-only, high-write table like `journal_lines`, offset
pages drift under concurrent inserts, causing skipped or duplicated rows.

---

## Security domain — API keys

**Package:** `finance.idem.core.security`

Entities and DTOs that model API key lifecycle and scope-based authorization. Zero Spring/JPA
imports — same rule as the ledger core.

### ApiScope

Fine-grained permission enum. A key can hold any combination of scopes. `ADMIN` does not
implicitly grant other scopes — callers must check each scope explicitly.

| Scope | Purpose |
|---|---|
| `TRANSACTIONS_READ` | Read transaction records |
| `TRANSACTIONS_WRITE` | Post new transactions |
| `ACCOUNTS_READ` | Read account metadata and balances |
| `ACCOUNTS_WRITE` | Create and update accounts |
| `AGENTS_EXECUTE` | Trigger agentic workflows |
| `AGENTS_AUDIT_READ` | Read agent execution audit trail |
| `RECONCILIATION_READ` | Read reconciliation reports |
| `RECONCILIATION_WRITE` | Write reconciliation adjustments |
| `COMPLIANCE_EXPORT` | Export compliance data |
| `WEBHOOK_MANAGE` | Manage webhook endpoints |
| `ADMIN` | Administrative access |

### ApiKey

Domain entity for an issued API credential. The raw key is never stored — only a BCrypt hash
and a 12-character prefix used for fast cache/DB lookup.

```
ApiKey(id: ApiKeyId, tenantId: TenantId, keyHash: String, prefix: String,
       scopes: Set<ApiScope>, createdAt: Instant, revokedAt: Instant? = null)

val isRevoked: Boolean                    // true when revokedAt is non-null
fun hasScope(scope: ApiScope): Boolean    // true when scope is in the key's scope set
```

`ApiKey.create()` is the factory for new keys. Keys are never hard-deleted — revocation sets
`revokedAt`. The raw key is returned exactly once at generation and never persisted.

### ValidatedApiKey

Lightweight DTO produced after a raw key clears validation (hash match + not revoked). Carries
only what downstream callers need — the tenant context and the active scope set.

```
ValidatedApiKey(tenantId: TenantId, scopes: Set<ApiScope>)
fun hasScope(scope: ApiScope): Boolean
```

`ValidatedApiKey` is returned by `ApiKeyService.validate()` in the infrastructure layer. The
service checks the Redis cache first (5-minute TTL, keyed by prefix); on a miss it falls back
to the database and re-populates the cache.

### ApiKeyRepository

```kotlin
interface ApiKeyRepository {
    fun save(apiKey: ApiKey): ApiKey
    fun findByPrefix(prefix: String): ApiKey?
    fun findById(id: ApiKeyId, tenantId: TenantId): ApiKey?
    fun findAllByTenantId(tenantId: TenantId): List<ApiKey>
}
```

`findByPrefix` takes no `tenantId` — the auth filter must read the table before the tenant
context is established. The bcrypt hash is the security boundary, not row-level isolation.
The `api_keys` table therefore carries no RLS policy.

`findAllByTenantId` returns all keys for a tenant (active and revoked) for use in key
management listings. Used by `ListApiKeysUseCase` / `GET /api/v1/api-keys` (requires `ADMIN`
scope). Key hashes are never included in API responses — the list endpoint exposes only
`id`, `prefix`, `scopes`, `createdAt`, and `revokedAt`.

---

## Chain domain — ChainCheckpoint

**Package:** `finance.idem.core.chain`

Tracks the last processed block per chain so the on-chain event reader can resume after a
restart without re-scanning from genesis.

```
ChainCheckpoint(chainKey: String, lastBlock: Long, updatedAt: Instant)
```

`chainKey` is a free-form string (e.g. `"EVM_1"`, `"EVM_8453"`, `"EVM_137"`) rather than the
`ChainId` enum. This distinguishes networks that share the same enum value but operate on
different chain IDs — Ethereum mainnet (1) and Base (8453) are both `ChainId.EVM`.

`ChainCheckpoint` carries no `tenantId` — chain state is global infrastructure, not
tenant-scoped. The `chain_checkpoint` table has no RLS policy.

### ChainCheckpointRepository

```kotlin
interface ChainCheckpointRepository {
    fun findByChainKey(chainKey: String): ChainCheckpoint?
    fun save(chainKey: String, lastBlock: Long)
}
```

`save()` is an upsert — calling it twice for the same `chainKey` overwrites `lastBlock` and
refreshes `updatedAt`.

---

## Reconciliation domain — Settlement

**Package:** `finance.idem.core.ledger`

Tracks the lifecycle of an on-chain settlement against the ledger: a customer-registered
expectation of an incoming transfer (`PENDING`), or an orphan on-chain receipt with no
matching expectation (`UNMATCHED`), auto-created by `BasicReconciliationService`. Purely
additive — zero changes to `MonetaryEntry`, `OnChainEntry`, `JournalLine`, or `Transaction`.
See `docs/reconciliation.md` for the matching algorithm.

### EntryStatus

| Value | Meaning |
|---|---|
| `PENDING` | A settlement expectation has been registered, awaiting a matching `OnChainEntry` |
| `SETTLED` | A `PENDING` expectation was matched to a posted `OnChainEntry` |
| `UNMATCHED` | A posted `OnChainEntry` had no matching `PENDING` expectation |
| `CANCELLED` | Reserved for a future "cancel expectation" API — not set by the matching engine |

### Settlement

```
Settlement(id: UUID, tenantId: TenantId, accountId: AccountId, amount: MonetaryAmount,
           token: StablecoinToken, chainId: ChainId, walletAddress: String,
           status: EntryStatus, matchedTransactionId: TransactionId? = null,
           txHash: String? = null, blockNumber: Long? = null, confirmedAt: Instant? = null,
           expectedFromAddress: String? = null,
           createdAt: Instant, createdBy: String)
```

Both PENDING rows (registered ahead of time, e.g. via a future `POST
/accounts/{id}/settlements`) and UNMATCHED rows (created reactively when no expectation
matches an incoming transfer) live in the same table. `matchedTransactionId`, `txHash`,
`blockNumber`, and `confirmedAt` are null on PENDING rows and populated when the row
transitions to SETTLED or is created as UNMATCHED. `Settlement` is a plain `data class`
with no `init` invariants — matching `JournalLine`'s style.

`expectedFromAddress` is an optional sender-address hint on a PENDING row: when set, it's
compared case-insensitively against the incoming `OnChainEntry.fromAddress` as a preferred
"tier 1" match ahead of the amount+FIFO fallback, and a disagreeing sender excludes that row
from matching entirely. Always `null` today — there is no endpoint yet to register it. See
`docs/reconciliation.md` for the full matching algorithm.

### SettlementRepository

```kotlin
interface SettlementRepository {
    fun save(settlement: Settlement): Settlement
    fun findById(id: UUID, tenantId: TenantId): Settlement?

    /** PENDING rows for tenant where accountId ∈ accountIds, matching
     * token/chainId/walletAddress, createdAt >= since. Ordered createdAt ASC. */
    fun findPendingCandidates(
        tenantId: TenantId,
        accountIds: Set<AccountId>,
        token: StablecoinToken,
        chainId: ChainId,
        walletAddress: String,
        since: Instant,
    ): List<Settlement>
}
```

`save()` is an upsert — transitioning a row from `PENDING` to `SETTLED` updates the
existing row in place rather than inserting a new one.

---

## Tenant domain — webhook configuration

**Package:** `finance.idem.application.tenant` / `finance.idem.application.port`

Per-tenant webhook delivery configuration consumed by `WebhookOutboxPoller` — each
tenant receives outbox events at their own URL, signed with their own secret. See
`docs/webhook-outbox-poller.md` for the delivery mechanism.

### TenantWebhookConfig

```kotlin
data class TenantWebhookConfig(val webhookUrl: String, val webhookSecret: String)
```

### TenantRepository

```kotlin
interface TenantRepository {
    fun findWebhookConfig(tenantId: TenantId): TenantWebhookConfig?
    fun upsertWebhookConfig(tenantId: TenantId, config: TenantWebhookConfig)
}
```

`findWebhookConfig` returns `null` when the webhook is not yet configured (no row, or
`webhook_url`/`webhook_secret` is null/blank) — not an error. The `tenants` table (V13)
has RLS enabled but **not forced**, mirroring `webhook_outbox` (V12): `WebhookOutboxPoller`
resolves any tenant's config as the table-owner role while iterating cross-tenant
dispatchable rows, with no `app.tenant_id` set.

`upsertWebhookConfig` inserts the tenant row if none exists, or updates `webhook_url` and
`webhook_secret` in place — `createdAt` is preserved on updates. Called by
`UpdateWebhookConfigService` / `PUT /api/v1/tenant/webhook` (requires `WEBHOOK_MANAGE` scope).
The SSRF guard (`SsrfWebhookUrlValidator`) rejects private-range and link-local URLs before
this method is called; a 32-byte `SecureRandom` hex secret is generated per call.

---

## Verification

```bash
mvn test -pl core          # unit tests for core domain
mvn verify                 # full reactor — Spring Modulith boundary check runs here
```

The Modulith boundary check fails the build if any `core` class imports a Spring type.
