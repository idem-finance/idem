# Idem — Travel Rule (IVMS 101) Flow

> Modules: `core.compliance`, `application.compliance`, `infrastructure.compliance`
> **FATF Travel Rule pre-flight check on every `OnChainEntry` above the USD 1,000 threshold.**
> Non-blocking by design: flagged transfers are queued for compliance review — the transaction
> is never rejected at the ledger level. The originating VASP (Idem) is responsible for
> resolving the gap before settlement is considered final.

---

## Role

The **FATF Travel Rule** (FATF Recommendation 16, updated 2019) requires Virtual Asset
Service Providers (VASPs) to exchange originator and beneficiary information for any
virtual asset transfer above a jurisdictional threshold — USD 1,000 in most markets.
The messaging standard is **IVMS 101** (Inter-VASP Messaging Standard 101).

| Without Travel Rule enforcement | With Idem's Travel Rule check |
|---|---|
| On-chain transfer accepted with no counterparty data | Every `OnChainEntry` above threshold is inspected |
| VASP cannot demonstrate FATF compliance | `TravelRuleData` (IVMS 101 payload) attached or transfer flagged |
| Audit trail missing originator/beneficiary | `compliance_queue` row created; webhook fired to tenant |
| Regulatory exposure for the institution | Clear compliance posture: resolved vs. pending queue |

Idem's implementation is **non-blocking**: a transfer without IVMS 101 data is
**not rejected** — it is committed to the ledger and simultaneously queued for
compliance follow-up. This mirrors real-world VASP practice where settlement cannot
wait for asynchronous counterparty message exchange.

---

## Type map

```mermaid
graph TD
    subgraph core.compliance
        TR["TravelRuleData\n──────────────────────\ntransferId: String\noriginator: VaspTransferParty\nbeneficiary: VaspTransferParty\ntransferAmount: MonetaryAmount\ntransferAsset: StablecoinToken\nthreshold: MonetaryAmount"]
        VTP["VaspTransferParty\n──────────────────────\nnaturalPerson?: NaturalPerson\nlegalPerson?: LegalPerson\naccountNumber: String\nvaspDid: String"]
        NP["NaturalPerson\n──────────────────────\nfirstName: String\nlastName: String\ndateOfBirth: LocalDate\nnationalId?: String\ncountry: String (ISO 3166-1 α2)"]
        LP["LegalPerson\n──────────────────────\nname: String\nregistrationNumber: String\ncountry: String (ISO 3166-1 α2)"]
        REPO["TravelRuleRepository «interface»\n──────────────────────\nsave(data, tenantId)\nfindByTransferId(transferId, tenantId)"]
    end

    subgraph application.compliance
        TV["TravelRuleValidator\n──────────────────────\nvalidate(entry, travelRuleData?)"]
        VR["TravelRuleValidationResult «sealed»\nExempt | Valid | MissingData | IncompleteData"]
        CQI["ComplianceQueueItem\n──────────────────────\nid: UUID\ntenantId: TenantId\ntxHash: String\nchainId: ChainId\nentryAmount: MonetaryAmount\nreason: ComplianceReason\nmissingFields: List‹String›\nenqueuedAt: Instant"]
        CR["ComplianceReason «enum»\nMISSING_DATA | INCOMPLETE_DATA"]
        CQR["ComplianceQueueRepository «interface»\n──────────────────────\nenqueue(item)"]
    end

    subgraph core.monetary
        OCE["OnChainEntry\n──────────────────────\namount: MonetaryAmount\ntoken: StablecoinToken\nchainId: ChainId\ntxHash: String\nblockNumber: Long\nwalletAddress: String\ntokenContract: String\ntravelRuleData?: TravelRuleData"]
    end

    TR  -->|"originator / beneficiary"| VTP
    VTP -->|"exactly one of"| NP
    VTP -->|"exactly one of"| LP
    OCE -->|"optional payload"| TR
    TV  -->|"reads"| OCE
    TV  -->|"reads"| TR
    TV  -->|"produces"| VR
    VR  -->|"MissingData / IncompleteData\nfactory"| CQI
    CQI -->|"reason"| CR
    CQI -->|"persisted via"| CQR
```

---

## FATF threshold — per-asset defaults

`TravelRuleData.defaultThresholdFor(asset)` maps the USD 1,000 FATF floor to each
stablecoin's native unit. BRZ is reviewed quarterly.

| Token | Threshold (native units) | Rationale |
|---|---|---|
| USDC | 1,000 | 1:1 USD peg |
| USDT | 1,000 | 1:1 USD peg |
| PYUSD | 1,000 | 1:1 USD peg |
| BRZ | 5,500 | ~BRL/USD 5.5 (reviewed quarterly) |

Callers may supply a custom threshold in `TravelRuleData`. If they do, they own
the responsibility of keeping it in the same denomination as the transfer asset.

---

## Validation outcomes

```mermaid
graph TD
    A(["OnChainEntry\n+ TravelRuleData? (nullable)"]) --> B{amount\n< threshold?}
    B -->|Yes| C(["✅ Exempt\n(no action needed)"])
    B -->|No — above threshold| D{travelRuleData\npresent?}
    D -->|No| E(["🚨 MissingData\nreason: MISSING_DATA\nentry + reason message"])
    D -->|Yes| F{VaspTransferParty\nconstructor passed?}
    F -->|"vaspDid blank\nor missing person\n(construction guard)"| G(["⚠️ IncompleteData\nreason: INCOMPLETE_DATA\nmissingFields list"])
    F -->|"valid construction\n(all invariants hold)"| H(["✅ Valid\ntravelRuleData attached"])
```

> **Note on `IncompleteData`**: `VaspTransferParty.init` already enforces non-blank
> `vaspDid` and exactly one of `naturalPerson`/`legalPerson` at construction time.
> This means `IncompleteData` is **currently unreachable** through normal domain
> construction — a validly-constructed `TravelRuleData` is always `Valid`.
> The variant is preserved as a forward compatibility slot for future IVMS 101
> extended-field checks (e.g., national ID presence, date-of-birth format, LEI
> verification for legal persons).

---

## Validation flow (detailed)

```mermaid
flowchart TD
    START(["TravelRuleValidator.validate(\n  entry: OnChainEntry,\n  travelRuleData: TravelRuleData?\n)"]) --> THRESHOLD["Resolve threshold:\ntravelRuleData?.threshold\n?: defaultThresholdFor(entry.token)"]

    THRESHOLD --> CMP{entry.amount\n< threshold?}

    CMP -->|"true\n(below threshold)"| EXEMPT(["return Exempt"])

    CMP -->|"false\n(at or above threshold)"| NULL{travelRuleData\n== null?}

    NULL -->|"null\n(no IVMS 101 payload)"| MISSING(["return MissingData(\n  entry = entry,\n  reason = "Travel rule data required\n           for transfers >= &dollar;{threshold}"\n)"])

    NULL -->|"non-null\n(payload present)"| VALID(["return Valid(\n  travelRuleData = travelRuleData\n)"])

    style EXEMPT fill:#d4edda,color:#155724
    style VALID fill:#d4edda,color:#155724
    style MISSING fill:#f8d7da,color:#721c24
```

---

## Transaction integration — where Travel Rule fits in `PostTransactionService`

```mermaid
sequenceDiagram
    autonumber
    participant API as REST Controller\n(api layer)
    participant SVC as PostTransactionService\n(infrastructure — @Transactional)
    participant TR as TravelRuleValidator\n(application — pure Kotlin)
    participant TXR as TransactionRepository\n(infrastructure)
    participant AUD as AuditRepository\n(infrastructure)
    participant OBX as WebhookOutboxRepository\n(infrastructure)
    participant CQR as ComplianceQueueRepository\n(infrastructure)
    participant DB as PostgreSQL\n(single commit)

    API->>SVC: execute(PostTransactionCommand)
    SVC->>SVC: idempotency check\n(idempotencyStore.tryRecord)
    SVC->>SVC: resolve accounts\n(accountRepository.findExistingIds)
    SVC->>SVC: build JournalLines\n(map cmd.lines → JournalLine)
    SVC->>SVC: Transaction.create() + validate()\n(double-entry invariant enforced)
    SVC->>AUD: save(AuditEntry) ← BEFORE any other write
    SVC->>TXR: save(transaction)
    SVC->>OBX: save(WebhookOutboxEntry.transactionCommitted)
    SVC->>SVC: reconciliationService.reconcile(transaction)

    Note over SVC,TR: Travel Rule pre-flight — runs for every OnChainEntry line
    loop for each JournalLine
        SVC->>SVC: cast line.monetaryEntry as? OnChainEntry
        SVC->>TR: validate(entry, entry.travelRuleData)
        TR-->>SVC: TravelRuleValidationResult
    end

    alt Any MissingData or IncompleteData results
        SVC->>CQR: enqueue(ComplianceQueueItem) for each flagged entry
        SVC->>OBX: save(WebhookOutboxEntry.travelRuleRequired)
    end

    Note over SVC,DB: All writes above commit atomically in one @Transactional
    SVC-->>API: Result.success(transactionId)
```

> **Atomicity guarantee**: the compliance queue write and the `compliance.travel_rule_required`
> webhook outbox write commit in the **same `@Transactional`** as the primary transaction.
> If any write fails, the entire operation rolls back — there are no orphaned queue rows
> without a corresponding transaction, and no transactions without a corresponding queue row.

---

## IVMS 101 domain model

```mermaid
classDiagram
    class TravelRuleData {
        +transferId: String
        +originator: VaspTransferParty
        +beneficiary: VaspTransferParty
        +transferAmount: MonetaryAmount
        +transferAsset: StablecoinToken
        +threshold: MonetaryAmount
        +isAboveThreshold() Boolean
        +defaultThresholdFor(asset) MonetaryAmount$
    }

    class VaspTransferParty {
        +naturalPerson: NaturalPerson? [0..1]
        +legalPerson: LegalPerson? [0..1]
        +accountNumber: String
        +vaspDid: String
        «init: exactly one of naturalPerson or legalPerson»
        «init: vaspDid must not be blank»
    }

    class NaturalPerson {
        +firstName: String
        +lastName: String
        +dateOfBirth: LocalDate
        +nationalId: String? [optional]
        +country: String [ISO 3166-1 α2]
    }

    class LegalPerson {
        +name: String
        +registrationNumber: String
        +country: String [ISO 3166-1 α2]
    }

    TravelRuleData "1" --> "2" VaspTransferParty : originator / beneficiary
    VaspTransferParty "1" --> "0..1" NaturalPerson
    VaspTransferParty "1" --> "0..1" LegalPerson
```

---

## Compliance queue lifecycle

Once a transfer is flagged (`MissingData` or `IncompleteData`), a `compliance_queue` row
is created. A compliance officer (or future automation) advances it through three statuses:

```mermaid
stateDiagram-v2
    [*] --> PENDING : ComplianceQueueRepository.enqueue()
    PENDING --> REVIEWED : Compliance officer opens the item\n(future API: PATCH /compliance/queue/{id}/review)
    REVIEWED --> CLEARED : Travel Rule data verified\nor exemption granted
    REVIEWED --> PENDING : Sent back for more information

    PENDING --> [*] : Will not self-resolve\n(manual action required)
    CLEARED --> [*] : Transfer considered compliant
```

| Status | Meaning |
|---|---|
| `PENDING` | Flagged; awaiting compliance review |
| `REVIEWED` | Under active review by compliance officer |
| `CLEARED` | Travel Rule satisfied or formal exemption granted |

> **Current state**: status transitions are not yet exposed through an API. The
> `compliance_queue` table is writable only by the application (via `enqueue`). A
> compliance review API (`GET/PATCH /compliance/queue`) is planned as a follow-up issue.

---

## Webhook event — `compliance.travel_rule_required`

When one or more journal lines in a transaction are flagged, the webhook outbox emits
a single `compliance.travel_rule_required` event for the whole transaction (not per line):

```json
{
  "eventType": "compliance.travel_rule_required",
  "transactionId": "txn_01J...",
  "tenantId": "...",
  "occurredAt": "2025-06-27T14:32:00Z"
}
```

This event fires alongside `transaction.committed` in the same atomic commit.
Tenants can subscribe to this event via their webhook endpoint to trigger their own
Travel Rule resolution workflow (e.g., sending an IVMS 101 message to the beneficiary VASP
via Notabene or another Travel Rule protocol provider).

```mermaid
sequenceDiagram
    participant SVC as PostTransactionService
    participant OBX as webhook_outbox (DB)
    participant POLL as WebhookOutboxPoller\n(@Scheduled every 5s)
    participant TENANT as Tenant Webhook\nEndpoint

    SVC->>OBX: INSERT transaction.committed
    SVC->>OBX: INSERT compliance.travel_rule_required
    Note over SVC,OBX: Both in same @Transactional commit

    POLL->>OBX: SELECT up to 50 PENDING rows
    POLL->>TENANT: POST {eventType: "transaction.committed"}
    POLL->>TENANT: POST {eventType: "compliance.travel_rule_required"}
    TENANT-->>POLL: 2xx
    POLL->>OBX: UPDATE status = DELIVERED

    Note over POLL,TENANT: Retry on failure:\nexponential backoff (5s→30s→2m→10m→1h)\nmax 5 retries, then dead-letter
```

---

## Database schema — `compliance_queue`

```sql
CREATE TABLE compliance_queue (
    id              UUID           NOT NULL DEFAULT gen_random_uuid(),
    tenant_id       UUID           NOT NULL,
    tx_hash         TEXT           NOT NULL,
    chain_id        TEXT           NOT NULL,
    entry_amount    NUMERIC(38,18) NOT NULL,
    reason          TEXT           NOT NULL
        CHECK (reason IN ('MISSING_DATA', 'INCOMPLETE_DATA')),
    missing_fields  JSONB          NOT NULL DEFAULT '[]',
    status          TEXT           NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'REVIEWED', 'CLEARED')),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT pk_compliance_queue PRIMARY KEY (id)
);

CREATE INDEX idx_compliance_queue_tenant ON compliance_queue (tenant_id, created_at DESC);

ALTER TABLE compliance_queue ENABLE ROW LEVEL SECURITY;
ALTER TABLE compliance_queue FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON compliance_queue
    USING (tenant_id = current_setting('app.tenant_id', true)::UUID);
```

**RLS**: every query is automatically scoped to the calling tenant's `app.tenant_id`.
No application code can accidentally read another tenant's compliance queue rows.

**`missing_fields` JSONB**: populated for `INCOMPLETE_DATA` rows (e.g.,
`["vaspDid", "naturalPerson.nationalId"]`). Empty array for `MISSING_DATA` rows
(there is no partial payload to inspect).

---

## End-to-end flow — full picture

```mermaid
flowchart TD
    subgraph "Originating VASP (Idem)"
        API(["POST /api/v1/transactions\n(Idempotency-Key + OnChainEntry\nwith or without TravelRuleData)"])
        SVC["PostTransactionService\n@Transactional"]
        TR["TravelRuleValidator\n(pure Kotlin)"]
        TX[("transactions\njournal_lines\naudit_log")]
        CQ[("compliance_queue\n(PENDING row)")]
        WO[("webhook_outbox\n(PENDING rows)")]
    end

    subgraph "Webhook delivery"
        POLL["WebhookOutboxPoller\n@Scheduled every 5s"]
        TENANT["Tenant webhook endpoint"]
    end

    subgraph "Beneficiary VASP (future)"
        NOTABENE["Travel Rule protocol\n(Notabene / OpenVASP / TRP)"]
        BVASP["Beneficiary VASP\nIVMS 101 response"]
    end

    subgraph "Compliance officer (future)"
        REVIEW["Compliance review API\n(not yet implemented)"]
        QUEUE_UPDATE["compliance_queue\nstatus → CLEARED"]
    end

    API --> SVC
    SVC --> TR
    TR -->|"Exempt / Valid"| TX
    TR -->|"MissingData"| TX
    TR -->|"MissingData"| CQ
    TX --> WO

    WO --> POLL
    POLL -->|"transaction.committed"| TENANT
    POLL -->|"compliance.travel_rule_required"| TENANT
    TENANT -->|"triggers"| NOTABENE
    NOTABENE <-->|"IVMS 101 exchange"| BVASP
    NOTABENE -->|"resolution confirmed"| REVIEW
    REVIEW --> QUEUE_UPDATE

    style CQ fill:#fff3cd,color:#856404
    style NOTABENE fill:#e2e3e5,color:#383d41
    style REVIEW fill:#e2e3e5,color:#383d41
    style QUEUE_UPDATE fill:#e2e3e5,color:#383d41
```

> Greyed-out boxes are **not yet implemented**. The current implementation covers everything
> up to and including the webhook delivery. Notabene integration and the compliance review API
> are planned follow-up issues.

---

## What is not yet implemented

| Gap | Description | Status |
|---|---|---|
| Notabene adapter | Send IVMS 101 payload to beneficiary VASP via Notabene sandbox | Not started — see `infrastructure.compliance` package placeholder |
| Compliance review API | `GET /compliance/queue` (paginated) + `PATCH /compliance/queue/{id}/review` | Not started |
| `IncompleteData` production path | Extended IVMS 101 field validation (LEI, national ID format, etc.) | Reserved; currently unreachable |
| Per-tenant thresholds | Override FATF default on a per-tenant basis (lower threshold for conservative tenants) | Not started |
| Automatic VASP discovery | DID-based VASP DID registry lookup for beneficiary VASP resolution | Not started |
| `TravelRuleData` submission API | `POST /compliance/travel-rule/{transferId}` for retroactive IVMS 101 data submission | Not started |

---

## Key invariants

### Non-blocking — transaction always commits
A missing Travel Rule payload **never causes a `Result.failure`**. The ledger entry is
committed unconditionally. The compliance obligation is tracked via the queue and resolved
asynchronously. This is intentional — rejecting at the ledger layer would require
synchronous cross-VASP communication, which is incompatible with on-chain settlement speed.

### One webhook per transaction, not per line
If a transaction has three `OnChainEntry` lines and all three are above threshold with no
IVMS 101 data, three `compliance_queue` rows are created — but only **one**
`compliance.travel_rule_required` webhook is emitted (for the transaction as a whole).

### FiatEntry lines are invisible to Travel Rule
`TravelRuleValidator.validate` only accepts `OnChainEntry`. The caller (`PostTransactionService`)
casts `line.monetaryEntry as? OnChainEntry` — fiat lines return `null` and are skipped silently.
The Travel Rule does not apply to ACH, PIX, WIRE, or SEPA entries.

### Threshold denominated in token units, not USD
`MonetaryAmount` is a raw `BigDecimal` with no currency tag. Thresholds are expressed
in the token's native unit. Comparing `entry.amount < threshold` is a same-denomination
comparison. The USD-equivalent logic lives in `defaultThresholdFor(asset)`, not in the
validator itself.

---

## Test coverage

| Test class | Type | What it covers |
|---|---|---|
| `TravelRuleValidatorTest` | Unit | 10 cases: Exempt ×3 (at/below threshold), MissingData ×3 (null payload above threshold), Valid ×4 (valid IVMS 101 with natural person, legal person, custom threshold, BRZ) |
| `ComplianceQueueItemTest` | Unit | 3 cases: `from(MissingData)` factory, `from(IncompleteData)` factory, unique ID per call |
| `ComplianceQueueRepositoryAdapterTest` | Integration (Testcontainers) | 3 cases: MISSING_DATA round-trip, INCOMPLETE_DATA JSONB serialization, tenant_id ownership isolation |
| `PostTransactionServiceTest` | Unit | 4 new cases: FiatEntry skip (no queue row), below-threshold skip, MissingData writes queue + webhook, Valid skips queue |
| `TravelRuleDataTest` | Unit | Constructor invariants, `isAboveThreshold`, `defaultThresholdFor` per asset |
| `VaspTransferPartyTest` | Unit | Both-null rejection, both-present rejection, blank vaspDid rejection, blank accountNumber rejection |

```bash
rtk test mvn test -pl core,application,infrastructure
```

---

## Related

- `docs/domain-model.md` — `MonetaryEntry`, `OnChainEntry`, `Transaction`
- `docs/webhook-outbox-poller.md` — outbox delivery and retry mechanics
- `core/compliance/TravelRuleData.kt` — IVMS 101 payload + threshold logic
- `core/compliance/VaspTransferParty.kt` — originator/beneficiary identity
- `application/compliance/TravelRuleValidator.kt` — validation logic
- `application/compliance/TravelRuleValidationResult.kt` — result sealed class
- `infrastructure/compliance/ComplianceQueueRepositoryAdapter.kt` — persistence
- `infrastructure/service/PostTransactionService.kt` — integration point
- `V23__create_compliance_queue.sql` — schema + RLS
- Issue [#172](https://github.com/idem-finance/idem/issues/172) — implementation
