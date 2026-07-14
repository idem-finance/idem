# idem

Double-entry ledger for institutions settling cross-border payments on stablecoin rails.

[![CI](https://github.com/idem-finance/idem/actions/workflows/ci.yml/badge.svg)](https://github.com/idem-finance/idem/actions/workflows/ci.yml)
[![License: FSL-1.1-ALv2](https://img.shields.io/badge/license-FSL--1.1--ALv2-blue)](LICENSE.md)
[![GitHub Stars](https://img.shields.io/github/stars/idem-finance/idem?style=social)](https://github.com/idem-finance/idem)

---

Idem is an API-first, event-sourced double-entry ledger built for fintechs and PSPs that move money across fiat and stablecoin rails simultaneously. It models both `FiatEntry` and `OnChainEntry` (EVM, Solana, Tron) in a single unified transaction, enforces debits-equal-credits at the domain layer, and reads on-chain transfers automatically via Alchemy and QuickNode webhooks. It is not a payment processor — it records and reconciles money movement, it does not initiate it.

---

## Why Idem

- **Fiat and on-chain in one double-entry model.** A single transaction can contain a PIX debit and a USDC credit on Base. No separate reconciliation step between your fiat ledger and a blockchain indexer.
- **On-chain entries auto-post.** Alchemy (EVM) and QuickNode (Solana) webhooks drive chain event ingestion. Tron is polled via Tronscan REST. Transfers to watched addresses create ledger entries automatically, idempotently keyed by `chainId:txHash`.
- **Scope-based API key auth with PostgreSQL RLS as the backstop.** Every request is validated against bcrypt-hashed keys cached in Redis. Even if auth is bypassed, PostgreSQL row-level security prevents cross-tenant data access.
- **Multi-replica safe out of the box.** Outbox polling and Tron chain polling are guarded by ShedLock-backed distributed locks. Single-instance deployments run without any coordination overhead.

---

## Quick start

**Prerequisites:** JDK 21, Maven 3.9+, Docker

```bash
# Start PostgreSQL 16 and Redis 7
make up          # or: docker compose up -d

# Build all modules (skips tests for speed)
make build       # or: ./mvnw install -DskipTests

# Seed the dev tenant and print your ADMIN API key
make seed

# Run the application
./mvnw spring-boot:run -pl app -Dspring-boot.run.profiles=dev
```

The API is available at `http://localhost:8081`. Interactive OpenAPI docs: `http://localhost:8081/swagger-ui.html`.

`make seed` creates a dev tenant (idempotent) and generates a fresh ADMIN-scoped API key printed to stdout. Copy the `IDEM_API_KEY=sk_live_...` value — it is shown exactly once.

> **Production:** API keys are created via `POST /api/v1/api-keys` using your ADMIN key. The raw value is returned once at creation and never stored.

### Post a transaction

```bash
curl -X POST http://localhost:8081/api/v1/transactions \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $IDEM_API_KEY" \
  -H "Idempotency-Key: tx-$(uuidgen)" \
  -d '{
    "lines": [
      {
        "accountId": "<debit-account-uuid>",
        "entryType": "DEBIT",
        "monetaryEntry": {
          "type": "FIAT",
          "amount": 1000.00,
          "currency": "USD",
          "rail": "WIRE"
        }
      },
      {
        "accountId": "<credit-account-uuid>",
        "entryType": "CREDIT",
        "monetaryEntry": {
          "type": "FIAT",
          "amount": 1000.00,
          "currency": "USD",
          "rail": "WIRE"
        }
      }
    ]
  }'
```

```json
{ "transactionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6" }
```

An on-chain entry looks like:

```json
{
  "accountId": "<account-uuid>",
  "entryType": "CREDIT",
  "monetaryEntry": {
    "type": "ONCHAIN",
    "amount": 1000.00,
    "token": "USDC",
    "chainId": "EVM",
    "txHash": "0xabc...",
    "blockNumber": 19500000,
    "walletAddress": "0xabc...",
    "tokenContract": "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
  }
}
```

### Query balance

```bash
curl http://localhost:8081/api/v1/accounts/<account-uuid>/balance \
  -H "X-API-Key: sk_test_devkey00000000000000000000"
```

Point-in-time balance: append `?asOf=2025-01-01T00:00:00Z`.

### Kotlin SDK

```kotlin
val client = IdemClient(
    baseUrl = "http://localhost:8081",
    apiKey  = "sk_test_devkey00000000000000000000",
)

val tx = client.postTransaction(
    PostTransactionRequest(
        lines = listOf(
            JournalLineRequest(
                accountId = debitAccountId,
                entryType = EntryType.DEBIT,
                monetaryEntry = FiatEntryRequest(
                    amount     = BigDecimal("1000.00"),
                    currency   = FiatCurrency.USD,
                    rail       = PaymentRail.WIRE,
                ),
            ),
            JournalLineRequest(
                accountId = creditAccountId,
                entryType = EntryType.CREDIT,
                monetaryEntry = FiatEntryRequest(
                    amount     = BigDecimal("1000.00"),
                    currency   = FiatCurrency.USD,
                    rail       = PaymentRail.WIRE,
                ),
            ),
        ),
    ),
    idempotencyKey = UUID.randomUUID().toString(),
)
```

### MCP server (AI agent integration)

Plug Idem directly into Claude Desktop, Claude Code, or any MCP-compatible agent runtime:

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

Available tools: `post_transaction`, `get_balance`, `list_entries`, `describe_account`, `reconcile_batch` (require `AGENTS_EXECUTE` scope), `rollback_workflow` (requires `AGENTS_ROLLBACK` scope), and `get_agent_audit_log` (requires `AGENTS_AUDIT_READ` scope). Full connection guide: [`docs/mcp-server.md`](docs/mcp-server.md).

---

## API reference

| Method | Path | Scope required | Description |
|--------|------|---------------|-------------|
| `POST` | `/api/v1/transactions` | `TRANSACTIONS_WRITE` | Post a balanced double-entry transaction |
| `GET` | `/api/v1/accounts/{id}/balance` | `ACCOUNTS_READ` | Current or point-in-time balance |
| `GET` | `/api/v1/accounts/{id}/entries` | `ACCOUNTS_READ` | Paginated reverse-chronological entry timeline |
| `GET` | `/api/v1/accounts/{id}/statement` | `ACCOUNTS_READ` | Statement with opening/closing balances |
| `POST` | `/internal/webhooks/alchemy` | — | Alchemy Notify inbound (HMAC-validated) |
| `POST` | `/internal/webhooks/quicknode` | — | QuickNode Streams inbound (HMAC-validated) |

Full OpenAPI spec available at `/v3/api-docs` when the app is running.

**Available scopes:** `TRANSACTIONS_READ`, `TRANSACTIONS_WRITE`, `ACCOUNTS_READ`, `ACCOUNTS_WRITE`, `AGENTS_EXECUTE`, `AGENTS_AUDIT_READ`, `RECONCILIATION_READ`, `RECONCILIATION_WRITE`, `COMPLIANCE_EXPORT`, `WEBHOOK_MANAGE`, `ADMIN`

---

## Architecture

Idem is a modular monolith (Spring Modulith). Module boundaries are enforced at compile time — violations fail the build.

```
app ──┬── api ──────────────┬── application ── core
      ├── infrastructure ───┘
      └── mcp

sdk-kotlin  (standalone HTTP client, no internal module deps)
```

**Dependency rule:** `app → {api, infrastructure, mcp} → application → core`. The `core` module has zero framework dependencies — pure Kotlin, compiles without Spring on the classpath.

**`MonetaryEntry` sealed class** is the central design decision: a single journal line carries either a `FiatEntry` (amount, currency, rail, bankReference) or an `OnChainEntry` (amount, token, chainId, txHash, blockNumber, walletAddress, tokenContract). The double-entry invariant — debits == credits per currency per transaction — is enforced in `Transaction.validate()` and never bypassed.

All side effects (audit log, webhook outbox) are written in the **same `@Transactional`** as the primary operation. No event bus. Webhook delivery runs via a `@Scheduled` outbox poller with exponential backoff (5s → 30s → 2m → 10m → 1h, max 5 attempts).

**New to Idem?** Start with the end-to-end walkthrough — [`docs/architecture-overview.md`](docs/architecture-overview.md) — which follows a transaction from input through double-entry validation, chain ingestion, reconciliation, webhooks, and queries, with diagrams.

Technical documentation for individual components lives in [`docs/`](docs/).

---

## Tech stack

| Component | Version |
|-----------|---------|
| Kotlin | 1.9.25 |
| JVM | 21 |
| Spring Boot | 3.5.15 |
| Spring Modulith | 1.4.11 |
| PostgreSQL | 16 |
| Redis | 7 |
| Web3j (EVM chain reader) | 4.12.0 |
| ShedLock (distributed scheduling) | 6.6.0 |
| Flyway | managed by Spring Boot parent |
| springdoc-openapi | 2.8.9 |

---

## Project status

Idem is under active development. The core ledger engine, API key authentication, chain readers (EVM, Solana, Tron), webhook outbox, reconciliation, Kotlin SDK, PolicyGuard (agentic policy evaluation, backed by a per-tenant/per-agent `PolicyRepository`), the agent audit trail (HMAC-signed `AgentAuditEvent`, written before execution), rollback via compensating transactions (saga pattern), and the full seven-tool MCP server (`post_transaction`, `get_balance`, `list_entries`, `describe_account`, `reconcile_batch`, `rollback_workflow`, `get_agent_audit_log`) are complete. **Not yet implemented:** Travel Rule (IVMS 101), LGPD export, and Keycloak dashboard login.

Not yet recommended for production use without independent review.

Live roadmap: [GitHub Milestones](https://github.com/idem-finance/idem/milestones) · [Open Issues](https://github.com/idem-finance/idem/issues)

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development setup, module rules, code style, the PR process, and the DCO sign-off requirement.

Quick version:

```bash
git commit -s -m "feat: describe the change"   # DCO sign-off, required on every commit
./mvnw verify                                   # full build + tests + JaCoCo coverage gate, required before PR
```

Tests that touch PostgreSQL or Redis use Testcontainers — Docker must be running.

---

## Telemetry

Idem collects anonymous, non-identifying usage data (a random installation UUID, bucketed tenant/entry counts, JVM version) sent once per week to `telemetry.idem.finance`. No transaction data, wallet addresses, amounts, or PII are ever collected.

Opt out via `application.yaml` or environment variable:

```yaml
idem:
  telemetry:
    enabled: false
```

```bash
IDEM_TELEMETRY_ENABLED=false
```

---

## Release verification

<details>
<summary>GPG signature (Maven artifacts)</summary>

Published Maven Central artifacts — `finance.idem:idem-core` and `finance.idem:idem-sdk-kotlin` — are GPG-signed.

| Field | Value |
|-------|-------|
| Owner | Idem Finance \<flaubert165@gmail.com\> |
| Key ID | `0ABC39374C2B51EC` |
| Fingerprint | `3E33 3148 F633 F474 9F6A 4DF3 0ABC 3939 4C2B 51EC` |
| Key server | `keys.openpgp.org` |

```bash
gpg --keyserver keys.openpgp.org --recv-keys 3E333148F633F4749F6A4DF30ABC39374C2B51EC
gpg --verify idem-core-0.1.0.jar.asc idem-core-0.1.0.jar
```

**Stewardship:** the private key is held by Idem Finance and used exclusively by the `release.yml` GitHub Actions workflow (via the `GPG_PRIVATE_KEY`/`GPG_PASSPHRASE` repo secrets) to sign artifacts on tag push. It is not held in plaintext on any individual's machine.

**Rotation plan:** the key is rotated at minimum every 2 years, or immediately if compromise is suspected. On rotation: a new key pair is generated, the new public key is published to `keys.openpgp.org`, this table is updated with the new fingerprint, and the old key is revoked on the keyserver. Artifacts signed under a prior key remain verifiable against that key's fingerprint in the corresponding release's git history.

SHA256 checksums for every release's signed jars and native binary are also attached to the corresponding [GitHub Release](https://github.com/idem-finance/idem/releases) as `checksums.txt`.

</details>

<details>
<summary>Cosign signature (container images)</summary>

Docker images are signed with [Sigstore Cosign](https://docs.sigstore.dev/cosign/overview/) via keyless signing (GitHub Actions OIDC), published to both GHCR and Docker Hub:

- `ghcr.io/idem-finance/idem` — JVM image only
- `id3mfin4nc3/idem` — JVM (`latest`, `{version}`) and GraalVM native (`native`, `native-{version}`) variants

```bash
cosign verify \
  --certificate-identity-regexp="https://github.com/idem-finance/idem/.github/workflows/release.yml@refs/tags/" \
  --certificate-oidc-issuer="https://token.actions.githubusercontent.com" \
  id3mfin4nc3/idem:v0.1.0
```

</details>

---

## License

[FSL-1.1-Apache-2.0](LICENSE.md) — free to use, modify, and self-host for any purpose that does not compete with Idem as a managed service. Converts to Apache 2.0 automatically two years after each release. Full license text: [fsl.software](https://fsl.software/FSL-1.1-Apache-2.0).

If you build a product on Idem and make it available to others, include an acknowledgement in your documentation: "Powered by [Idem](https://github.com/idem-finance/idem)".

---

## Links

- Website: [idem.finance](https://idem.finance)
- X: [@idem_finance](https://x.com/idem_finance)
- Documentation: coming soon
