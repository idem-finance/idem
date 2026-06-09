# Idem — Solana Chain Reader

> Infrastructure module (`finance.idem.infrastructure.chain`).
> Fallback/recovery reader: replays missed Solana SPL token transfers from the last
> `ChainCheckpoint` on startup. Primary detection is handled by `SolanaWebSocketManager` (#74).

---

## Why raw JSON-RPC (no SDK)

Both Kotlin/Java Solana libraries were evaluated at integration time and are blocked:

| Library | Version | Blocker |
|---|---|---|
| **sol4k** | 0.7.0 | `getTransaction` is not implemented — cannot read `postTokenBalances` |
| **SolanaJ** | 1.28.0 | `TokenBalance` missing `owner` field (open [issue #104](https://github.com/skynetcap/solanaj/issues/104)) |

Without `owner`, there is no way to match a token balance entry to a watched wallet address — it is the sole filter criterion.

`java.net.http.HttpClient` + Jackson is the intentional choice until one of these libraries closes its gap. Re-evaluate when sol4k ships `getTransaction` or SolanaJ fixes `TokenBalance.owner`.

---

## Component overview

```mermaid
graph TD
    subgraph core
        ChainCheckpoint["ChainCheckpoint\n(chainKey, lastBlock)"]
        OnChainEntry["OnChainEntry\n(amount, token, chainId,\ntxHash, blockNumber,\nwalletAddress, tokenContract)"]
    end

    subgraph infrastructure.chain
        CR["ChainReader\n«interface»"]
        SCR["SolanaChainReader"]
        ECR["EvmChainReader"]
        FACTORY["EvmChainReaderFactory\n@Configuration"]
        WA["WatchedAddress\n(chainKey, walletAddress,\ntokenContract, token,\ntenantId, debitAccountId,\ncreditAccountId)"]
        DT["DetectedTransfer\n(idempotencyKey, entry, watchedAddress)"]
        WAR["WatchedAddressRepository\n«interface»"]
    end

    subgraph infrastructure.persistence.chain
        CCP["ChainCheckpointRepositoryAdapter"]
        WAP["WatchedAddressRepositoryAdapter"]
    end

    subgraph orchestrator["ChainReaderOrchestrator (pending — #76)"]
        ORCH["ApplicationStartedEvent\n→ replay missed slots\nfrom last ChainCheckpoint\n(fallback/recovery only)"]
    end

    FACTORY -->|"creates"| SCR
    FACTORY -->|"creates"| ECR
    SCR -->|"implements"| CR
    ECR -->|"implements"| CR
    SCR -->|"queries"| WAR
    ECR -->|"queries"| WAR
    WAR -->|"adapter"| WAP
    SCR -->|"produces"| DT
    DT -->|"entry field"| OnChainEntry
    ORCH -->|"calls poll(checkpoint)\non startup only"| CR
    ORCH -->|"reads/saves"| CCP
    CCP -->|"persists"| ChainCheckpoint
```

`EvmChainReaderFactory` is the Spring `@Configuration` that wires `SolanaChainReader` alongside the EVM and Tron readers at startup. All readers are returned as a single `List<ChainReader>` bean. `ChainReaderOrchestrator` (#76) calls `poll(checkpoint)` on `SolanaChainReader` once on `ApplicationStartedEvent` to replay any slots missed while the application was down — it is not called on a recurring schedule.

---

## Polling workflow

> **Architecture note (revised Jun 2026):** `SolanaChainReader` is a **fallback/recovery reader**.
> The primary Solana event source is `SolanaWebSocketManager` (issue #74).
> `SolanaChainReader.poll()` is called once on startup by `ChainReaderOrchestrator` (#76)
> to replay any slots missed while the application was down.

```mermaid
sequenceDiagram
    participant Orchestrator as ChainReaderOrchestrator\n(startup recovery)
    participant SCR as SolanaChainReader
    participant RPC as Solana JSON-RPC
    participant WA as WatchedAddressRepository
    participant PS as PostTransactionService
    participant DB as PostgreSQL

    Orchestrator->>DB: findByChainKey("SOLANA") → lastBlock (checkpoint)
    Orchestrator->>SCR: poll(checkpoint)
    SCR->>WA: findByChainKey("SOLANA")
    WA-->>SCR: List<WatchedAddress>

    loop for each watched address
        SCR->>RPC: getSignaturesForAddress(address, limit, before?)
        RPC-->>SCR: List<SignatureInfo> (newest-first)
        SCR->>SCR: takeWhile { slot > checkpoint }
        alt full page (size == pageSize)
            SCR->>RPC: getSignaturesForAddress(..., before=lastSig)
            RPC-->>SCR: next page
        end
        SCR->>SCR: sortBy { slot } → oldest-first

        loop for each relevant signature (err == null)
            SCR->>RPC: getTransaction(signature)
            RPC-->>SCR: SolanaTransactionResult
            SCR->>SCR: decodeTransfer(tx, sig, slot, watchedAddress)
            alt transfer matches watched address + token
                SCR-->>Orchestrator: DetectedTransfer
            end
        end
    end

    loop for each DetectedTransfer
        Orchestrator->>PS: execute(PostTransactionCommand, idempotencyKey="SOLANA:{txHash}")
        PS->>DB: save Transaction + AuditEntry + WebhookOutbox (one @Transactional)
    end

    Orchestrator->>DB: save ChainCheckpoint(lastBlock = maxSlot)
```

The checkpoint advance and the ledger write must happen in the same transaction to prevent a crash between the two from causing duplicate or missing entries. The idempotency key (`SOLANA:{txHash}`) is the safety net for any re-scan — the second attempt returns the cached result without re-executing.

---

## Pagination algorithm

Solana's `getSignaturesForAddress` returns signatures newest-first, paginated via a `before` cursor (exclusive). A single poll must collect all signatures above the checkpoint before advancing.

```mermaid
flowchart TD
    A([start: before = null]) --> B["fetch page\ngetSignaturesForAddress(address, limit, before)"]
    B --> C{page empty?}
    C -- yes --> Z([return sorted list])
    C -- no --> D["takeWhile { slot > checkpoint }\n→ relevant"]
    D --> E[all += relevant]
    E --> F{partial page?\npage.size < pageSize\nOR relevant.size < page.size}
    F -- yes --> Z
    F -- no --> G["before = page.last().signature"]
    G --> B
    Z --> H["sortBy { slot }  ← oldest-first"]
```

**Why oldest-first after collection:** the orchestrator advances the checkpoint to the highest slot seen. Processing in ascending order means a crash mid-batch leaves the checkpoint at the last successfully committed slot — the next run re-processes only the tail, not the whole page.

---

## Transfer decode decision tree

```mermaid
flowchart TD
    A([decodeTransfer called]) --> B{meta == null?}
    B -- yes --> SKIP([return null])
    B -- no --> C{meta.err != null?}
    C -- yes --> SKIP
    C -- no --> D{postTokenBalances\nhas entry where\nowner == walletAddress\nAND mint == tokenContract?}
    D -- no --> E{any entry\nwith matching mint\nbut null owner?}
    E -- yes --> WARN["WARN: legacy tx format\n(null owner)\nskip"] --> SKIP
    E -- no --> SKIP
    D -- yes --> F["receiving = matched entry"]
    F --> G{token is\nUSDC or USDT?}
    G -- no --> ERR1["ERROR: unsupported token\nskip"] --> SKIP
    G -- yes --> H{receiving.decimals\n== 6?}
    H -- no --> ERR2["ERROR: unexpected decimals\nskip"] --> SKIP
    H -- yes --> I{postAmount\nparseable as Long?}
    I -- no --> WARN2["WARN: unparseable amount\nskip"] --> SKIP
    I -- yes --> J["delta = postAmount - preAmount\n(0 if no matching preBalance)"]
    J --> K{delta <= 0?}
    K -- yes --> SKIP
    K -- no --> L["amount = delta × 10⁻⁶\nMonetaryAmount.of(...)"]
    L --> M([return DetectedTransfer])
```

**Key invariants:**
- `owner` match is case-insensitive — Solana addresses are base58 and wallets may be stored with mixed case.
- `decimals` is validated against the known constant (6 for USDC/USDT) — a mismatch indicates an unexpected token or node behaviour; skip and log rather than silently produce a wrong monetary amount.
- `delta` is always `post - pre`; pre defaults to 0 if the account did not hold any tokens before the transaction.

---

## Supported tokens

| Token | Mint address (mainnet) | Decimals | `StablecoinToken` |
|---|---|---|---|
| USDC | `EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v` | 6 | `USDC` |
| USDT | `Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB` | 6 | `USDT` |

Any other token encountered on a watched address is logged at ERROR level and skipped. To add a new token: extend the `when` branch in `SolanaChainReader.decodeTransfer`, update `knownDecimals`, and add the mint to `WatchedAddress` records.

---

## Idempotency

Every detected transfer gets the idempotency key `SOLANA:{txHash}`.

- A transaction hash on Solana is unique per transaction — there can be only one `postTokenBalances` result for a given hash.
- On a re-scan (restart, pagination overlap), `PostTransactionService` sees the duplicate key and returns the cached `TransactionId` without executing again.
- The checkpoint is the primary guard; idempotency is the secondary guard for the overlap window.

---

## Configuration

Properties are bound under `idem.chain` via `@ConfigurationProperties`:

```yaml
idem:
  chain:
    solana:
      rpc-url: https://your-quicknode-endpoint.quiknode.pro/your-key/
```

`SolanaChainReader` is only instantiated when `idem.chain.solana.rpc-url` is non-blank (see `EvmChainReaderFactory.chainReaders()`). Omitting the property disables the Solana reader at runtime — no bean, no polling.

The page size defaults to 1000 (the Solana RPC maximum). Override via the constructor for testing:

```kotlin
SolanaChainReader(rpcUrl, watchedAddressRepository, signaturePageSize = 100)
```

---

## RPC calls used

| Method | Purpose | Pagination |
|---|---|---|
| `getSignaturesForAddress` | Fetch confirmed signature list for a wallet | `before` cursor (exclusive), newest-first |
| `getTransaction` | Fetch full transaction + token balance deltas | No pagination — one call per signature |

Both are called with `encoding=json`, `commitment=confirmed`, and `maxSupportedTransactionVersion=0`. Versioned transactions (v0) with address lookup tables are supported at the RPC level; the reader only reads `postTokenBalances` and `preTokenBalances`, which are resolved by the node before returning.

---

## Lifecycle and shutdown

`SolanaChainReader` implements `java.io.Closeable`. `EvmChainReaderFactory` is annotated `@PreDestroy` and calls `readers.filterIsInstance<Closeable>().forEach(Closeable::close)` on application shutdown. This closes the underlying `HttpClient` connection pool cleanly.

---

## Error handling contract

| Condition | Behaviour |
|---|---|
| RPC returns non-200 HTTP | Log WARN, return `null` / empty list — caller continues |
| JSON parse failure | Log WARN with exception message, return `null` / empty list |
| Null `meta` or non-null `meta.err` | Skip silently (failed on-chain tx — not an error) |
| `owner == null` on a matching mint | Log WARN (legacy tx format), skip |
| Unsupported token type | Log ERROR, skip |
| Unexpected decimals | Log ERROR, skip |
| Unparseable `amount` string | Log WARN, skip |
| `delta <= 0` | Skip silently (outgoing transfer or no net change) |

`ChainReaderOrchestrator` must catch and log all exceptions thrown by `poll()` without propagating — a single failing chain reader must not abort the startup recovery for other chains.

---

## Test coverage

| Test class | Type | What it covers |
|---|---|---|
| `SolanaChainReaderTest` | Unit (Mockito) | `decodeTransfer` for: decimals mismatch, unsupported token (BRZ), unparseable amount, null owner legacy tx, `Closeable` smoke test |
| `SolanaChainReaderIntegrationTest` | Integration (WireMock) | Full `poll()` path: happy-path USDC detection, checkpoint filter, empty signature list, failed tx skip, signature error skip, two-page pagination with slot boundary |
| `EvmChainReaderFactoryTest` | Unit | `SolanaChainReader` registered when `rpc-url` is set; factory shutdown closes `Closeable` readers |

Run with:

```bash
rtk test mvn test -pl infrastructure
```

---

## Related

- `docs/domain-model.md` — `ChainCheckpoint`, `OnChainEntry`, `MonetaryEntry` sealed class
- `docs/evm-chain-reader.md` — EVM counterpart (Alchemy webhook primary, Web3j fallback)
- `docs/tron-chain-reader.md` — Tron counterpart (Tronscan REST polling — primary and only)
- Issues [#48](https://github.com/idem-finance/idem/issues/48), [#74](https://github.com/idem-finance/idem/issues/74), [#76](https://github.com/idem-finance/idem/issues/76)
- PR [#70](https://github.com/idem-finance/idem/pull/70) — review findings and fixes applied
