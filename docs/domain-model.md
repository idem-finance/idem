# Idem — Core Domain Model

> Reference spec for the `core` module. Read before implementing issues #4–#8.
> Every class here must be pure Kotlin — zero Spring, JPA, or Kafka imports.
> Spring Modulith enforces this at build time and fails CI on violation.

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

## Layer 1 — Primitives (Issue #4)

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

**`MonetaryAmount`** wraps `BigDecimal`, enforces scale ≤ 18 (covers any stablecoin mantissa),
and exposes `plus`, `minus`, `isZero`. All monetary arithmetic goes through here — once,
centrally, with consistent scale rules.

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

## Layer 2 — MonetaryEntry (Issue #5)

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
                   blockNumber: Long, walletAddress: String, tokenContract: String)
```

The compiler forces every `when` expression to handle both cases — no forgotten branch, no
runtime cast. The chain reader produces `OnChainEntry`; a PIX webhook produces `FiatEntry`.
Both flow through the same `JournalLine → Transaction` path.

**Invariants enforced in `init` (throws `LedgerInvariantViolation`):**
- `amount > 0` — zero or negative amounts are a programming error
- `txHash.isNotBlank()` for `OnChainEntry`
- `walletAddress.isNotBlank()` and `tokenContract.isNotBlank()` for `OnChainEntry`

---

## Layer 3 — Account (Issue #6)

**Package:** `finance.idem.core.ledger`

A named slot in the chart of accounts. **No balance field** — balance is always derived by
summing journal lines. The account record is metadata; journal lines are the source of truth.

```
Account(id: AccountId, tenantId: TenantId, name: String, currency: FiatCurrency,
        type: AccountType, normalBalance: EntryType, createdAt: Instant)
```

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

## Layer 3 — JournalLine (Issue #6)

**Package:** `finance.idem.core.ledger`

One side of a double-entry posting. Never exists alone — always belongs to a `Transaction`
with at least one counterpart.

```
JournalLine(id: UUID, transactionId: TransactionId, accountId: AccountId,
            entryType: EntryType, monetaryEntry: MonetaryEntry, description: String? = null)
```

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

## Layer 4 — Transaction aggregate (Issue #7)

**Package:** `finance.idem.core.ledger`

The aggregate root. Unit of atomicity — all lines commit together or not at all. Owns the
double-entry invariant.

```
Transaction(id: TransactionId, tenantId: TenantId, idempotencyKey: String,
            lines: List<JournalLine>, status: TransactionStatus,
            agentContext: AgentContext? = null, metadata: Map<String, String> = emptyMap(),
            occurredAt: Instant)
```

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

## Layer 5 — Repository interfaces (Issue #8)

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
```

`tenantId` is always an explicit parameter — never assumed from context. The infrastructure
adapter activates PostgreSQL RLS via `SET LOCAL app.tenant_id`, but the interface signature
makes the multi-tenancy contract visible to every caller.

---

## Implementation order

Dependency order — each layer depends on the one above it:

1. **#4** primitives + enums (no dependencies)
2. **#5** `MonetaryEntry` (depends on `MonetaryAmount`, currency/token/rail enums)
3. **#6** `Account` + `JournalLine` (depends on all enums + `MonetaryEntry`)
4. **#7** `Transaction` aggregate (depends on `JournalLine`, typed IDs)
5. **#8** repository interfaces (depends on `Account`, `Transaction`, typed IDs)

---

## Verification

```bash
rtk test mvn test -pl core          # after each issue
rtk test mvn verify                 # full reactor — Modulith boundary check runs here
```
