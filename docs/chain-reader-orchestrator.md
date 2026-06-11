# Idem — Chain Reader Orchestrator

> Infrastructure module (`finance.idem.infrastructure.chain`).
> **Central wiring point for all on-chain event sources.** No event bus — the
> orchestrator drives every `ChainReader` directly via two triggers: a one-time
> startup recovery sweep and a recurring Tron poll.

---

## Role

Before #76, `EvmChainReader`, `SolanaChainReader`, and `TronChainReader` were fully
implemented and individually tested, but nothing in the running application ever
called `poll()`. `ChainReaderOrchestrator` closes that gap with two
`@Component`-scoped triggers over the same `List<ChainReader>` bean
(`EvmChainReaderFactory.chainReaders()`):

| Trigger | When | Readers polled | `createdBy` |
|---|---|---|---|
| `onApplicationStarted()` | Once, on `ApplicationStartedEvent` | every reader where `chainKey != "TRON"` (`EVM_1`, `EVM_8453`, `EVM_137`, `SOLANA`) | `"chain-recovery"` |
| `pollTron()` | Recurring `@Scheduled(fixedDelayString = "${idem.chain.tron.polling-interval-ms:5000}")` | every reader where `chainKey == "TRON"` | `"tron-poller"` |

EVM and Solana are recovery-only by design — their primary, real-time paths are
the `AlchemyWebhookService` (#73) and `QuickNodeWebhookService` (#74) HTTP
receivers, which post entries and advance checkpoints as transfers happen. The
orchestrator's startup sweep exists purely to replay anything missed while the
app was down. Tron has no webhook API, so the scheduled poll is its **only**
mechanism — see `docs/tron-chain-reader.md`.

---

## Component overview

```mermaid
graph TD
    subgraph core
        ChainCheckpoint["ChainCheckpoint\n(chainKey, lastBlock)"]
    end

    subgraph application
        PTU["PostTransactionUseCase"]
    end

    subgraph infrastructure.chain
        CR["ChainReader «interface»\n(EVM_1, EVM_8453, EVM_137, SOLANA, TRON)"]
        ORCH["ChainReaderOrchestrator"]
        DT["DetectedTransfer.toCommand(createdBy)"]
    end

    subgraph triggers
        ASE["ApplicationStartedEvent\n(once, at boot)"]
        SCHED["@Scheduled fixedDelay\nidem.chain.tron.polling-interval-ms"]
    end

    ASE -->|"onApplicationStarted()"| ORCH
    SCHED -->|"pollTron()"| ORCH
    ORCH -->|"findByChainKey / save"| ChainCheckpoint
    ORCH -->|"poll(checkpoint)"| CR
    CR -->|"List<DetectedTransfer>"| ORCH
    ORCH -->|"toCommand(createdBy)"| DT
    DT -->|"execute(PostTransactionCommand)"| PTU
```

---

## Startup recovery (EVM + Solana)

```mermaid
sequenceDiagram
    autonumber
    participant Boot as Spring Boot\n(ApplicationStartedEvent)
    participant ORCH as ChainReaderOrchestrator
    participant CR as ChainReader\n(EVM_1 / EVM_8453 / EVM_137 / SOLANA)
    participant CKP as ChainCheckpointRepository
    participant PTU as PostTransactionUseCase
    participant DB as PostgreSQL

    Boot->>ORCH: ApplicationStartedEvent
    loop for each reader where chainKey != "TRON"
        ORCH->>CKP: findByChainKey(chainKey) → lastBlock (or 0)
        ORCH->>CR: poll(checkpoint)
        CR-->>ORCH: List<DetectedTransfer>
        loop for each transfer
            ORCH->>PTU: execute(transfer.toCommand("chain-recovery"))
            PTU->>DB: save Transaction + AuditEntry + WebhookOutbox (one @Transactional)
        end
        alt transfers found
            ORCH->>CKP: save(chainKey, max(blockNumber))
        else no transfers
            Note over ORCH,CKP: checkpoint left unchanged
        end
    end
```

---

## Tron scheduled poll

```mermaid
sequenceDiagram
    autonumber
    participant SCHED as @Scheduled\n(fixedDelay = idem.chain.tron.polling-interval-ms)
    participant ORCH as ChainReaderOrchestrator
    participant TCR as TronChainReader\n(chainKey = "TRON")
    participant CKP as ChainCheckpointRepository
    participant PTU as PostTransactionUseCase
    participant DB as PostgreSQL

    loop every polling-interval-ms
        SCHED->>ORCH: pollTron()
        ORCH->>CKP: findByChainKey("TRON") → lastBlock (or 0)
        ORCH->>TCR: poll(checkpoint)
        TCR-->>ORCH: List<DetectedTransfer>
        loop for each transfer
            ORCH->>PTU: execute(transfer.toCommand("tron-poller"))
            PTU->>DB: save Transaction + AuditEntry + WebhookOutbox (one @Transactional)
        end
        alt transfers found
            ORCH->>CKP: save("TRON", max(blockNumber))
        end
    end
```

---

## Exception isolation

`pollAndPost(reader, createdBy)` wraps the entire poll-decode-post-checkpoint
sequence for a single reader in `try/catch`. A `RuntimeException` from one
reader (RPC down, malformed response, DB hiccup) is logged as
`"${reader.chainKey}: poll failed"` and does **not**:

- stop the `forEach` over the remaining readers in the same trigger, or
- propagate out of `onApplicationStarted()` / `pollTron()`.

Within the loop, a failed `postTransactionUseCase.execute()` (a `Result.failure`,
e.g. a double-entry validation error) is logged individually via `onFailure` —
this does not abort the loop over the remaining transfers, and does not block
the checkpoint advancement below. This mirrors the convention already
established by `AlchemyWebhookService`/`QuickNodeWebhookService`: log and move
on, rather than inventing a stricter policy for the orchestrator.

---

## Checkpoint advancement

```kotlin
val newCheckpoint = transfers.maxOfOrNull { it.entry.blockNumber } ?: checkpoint
if (newCheckpoint > checkpoint) {
    chainCheckpointRepository.save(reader.chainKey, newCheckpoint)
}
```

The checkpoint advances to the highest `blockNumber` among the transfers
returned by `poll()`. If `poll()` returns an empty list, the checkpoint is left
unchanged.

**Known limitation**: for the EVM/Solana recovery sweep, an empty result means
the checkpoint stays put — the next restart re-scans the same (now wider) block
range. This is acceptable because recovery is fallback-only (the webhook paths
keep checkpoints current during normal operation), and fixing it would require
widening the `ChainReader` interface so `poll()` can report a high-water-mark
independent of `List<DetectedTransfer>` — out of scope for #76 and would touch
three already-tested readers.

---

## Configuration

```yaml
idem:
  chain:
    tron:
      api-url: https://apilist.tronscan.org
      polling-interval-ms: 5000
```

| Property | Default | Notes |
|---|---|---|
| `idem.chain.tron.polling-interval-ms` | `5000` | `@Scheduled(fixedDelayString = ...)` interval for `pollTron()`. Tron's block time is ~3s. Integration tests override to `200` for fast scheduling. |

`onApplicationStarted()` has no interval — it fires exactly once per process
lifetime, regardless of how many EVM/Solana readers are configured.

---

## Scope clarification (#76 vs. #74)

Issue #76's original text referenced a dependency on "#74 (SolanaWebSocketManager)"
and asked the orchestrator to start/stop a WebSocket lifecycle on application
events. No `SolanaWebSocketManager` class exists — #74 was built as
`QuickNodeWebhookService`, an HTTP webhook receiver with no lifecycle to manage
(see `docs/solana-webhook-receiver.md`). `ChainReaderOrchestrator` therefore does
**not** manage any WebSocket connection; `SolanaChainReader`'s only invocation is
the one-time startup recovery poll described above. This matches the M2-3
chain-reader rules already documented in `CLAUDE.md`.

---

## Error handling

| Category | Behaviour |
|---|---|
| `reader.poll()` throws | Logged as `"${chainKey}: poll failed"`, exception swallowed — other readers in the same trigger still run |
| `postTransactionUseCase.execute()` returns `Result.failure` | Logged per-transfer with `idempotencyKey` and error message — remaining transfers and checkpoint advancement proceed |
| `poll()` returns empty list | Checkpoint left unchanged, no-op |

---

## Test coverage

| Test class | Type |
|---|---|
| `ChainReaderOrchestratorTest` | Unit (Mockito-Kotlin) — dispatch by `chainKey`, `createdBy` wiring, checkpoint advancement, exception isolation |
| `ChainReaderOrchestratorIntegrationTest` | Integration (Testcontainers Postgres) — end-to-end startup recovery and scheduled Tron poll against real `ChainCheckpointRepository`/`TransactionRepository` |

```bash
rtk test mvn test -pl infrastructure
```

---

## Related

- `docs/evm-chain-reader.md` — EVM recovery reader (Alchemy webhook primary, Web3j fallback)
- `docs/solana-chain-reader.md` — Solana recovery reader (QuickNode webhook primary, raw JSON-RPC fallback)
- `docs/tron-chain-reader.md` — Tron's only mechanism (Tronscan REST polling)
- `docs/domain-model.md` — `ChainCheckpoint`, `OnChainEntry`, `MonetaryEntry` sealed class
- `infrastructure/chain/EvmChainReaderFactory.kt` — factory that wires the `List<ChainReader>` bean
- Issue [#76](https://github.com/idem-finance/idem/issues/76)
