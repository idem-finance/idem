# Idem

Open-source, event-sourced double-entry ledger for institutions settling cross-border payments on stablecoin rails. Handles fiat and on-chain entries natively in one unified model, with a first-class agentic execution layer.

Licensed under [FSL-1.1-Apache-2.0](LICENSE.md). Converts to Apache 2.0 after two years.

---

## Requirements

- Java 21+
- Docker (for local PostgreSQL and Redis)
- Maven 3.9+

## Quick start

```bash
# Start backing services
docker compose up -d

# Build all modules
./mvnw install -DskipTests

# Run with seed data
mvn spring-boot:run -pl app -Dspring-boot.run.profiles=dev,seed

# The API is available at http://localhost:8081
```

## Running tests

```bash
mvn test                       # all modules
mvn test -pl core              # single module
mvn verify                     # full build + tests + coverage checks
```

## Documentation

- [`STRATEGY.md`](STRATEGY.md) — full product context and roadmap
- [`CONTEXT.md`](CONTEXT.md) — quick context card for new sessions
- [`docs/`](docs/) — component-level technical docs

---

## Telemetry

Idem collects **anonymous, non-identifying** usage data to help prioritise development. No personal data, business data, or identifying information is ever collected.

### What is collected

| Field | Description |
|---|---|
| `installationId` | A random UUID generated on first startup and stored locally in your database. It is never tied to any person, organisation, or account. |
| `idemVersion` | The idem version string (e.g. `0.1.0`). |
| `javaVersion` | The JVM version string (e.g. `21.0.3`). |
| `tenantBucket` | A bucketed tenant count: `1`, `2-10`, `11-50`, or `50+`. Never the exact count. |
| `entryBucket` | A bucketed journal-line count using the same buckets. Never the exact count. |

A single HTTP POST is sent to `https://telemetry.idem.finance/ping` once per week (every Monday at 01:00 UTC).

### What is NOT collected

- Tenant IDs, names, or any identifying tenant metadata
- Account IDs, wallet addresses, or transaction data
- Entry amounts or currency information
- IP addresses or network information
- Any user identities or PII of any kind

### How to disable

Set the following property in your `application.yaml`:

```yaml
idem:
  telemetry:
    enabled: false
```

Or via environment variable:

```bash
IDEM_TELEMETRY_ENABLED=false
```

When disabled, no network connection is attempted and no data is collected.

### Why opt-out by default?

Idem is open-source and self-hosted. Without any signal, it is impossible to know how many installations are running, which versions are in production, or whether deployments skew toward small teams or larger organisations. This shapes every prioritisation decision — from which Java versions to support to how aggressively to deprecate old APIs.

The data collected has zero privacy cost: a random UUID and two bucketed counters reveal nothing about your business. If you still prefer to opt out, the single property above is all you need.

---

## Attribution

If you build a product or service on top of Idem and make it available to others, include a visible acknowledgement in your documentation, "about" screen, or equivalent location — for example:

> Powered by [Idem](https://github.com/idem-finance/idem)

This requirement is part of the FSL-1.1-Apache-2.0 license and applies during the FSL window (the first two years after each release). It does not apply to purely internal deployments.
