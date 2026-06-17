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

## Verifying Releases

### Maven artifacts (GPG)

All release JARs published under `finance.idem` on Maven Central are GPG-signed.

**Signing key**

| Field       | Value                                              |
|-------------|-----------------------------------------------------|
| Owner       | Idem Finance \<flaubert165@gmail.com\>             |
| Key ID      | `0ABC39374C2B51EC`                                 |
| Fingerprint | `3E33 3148 F633 F474 9F6A 4DF3 0ABC 3939 4C2B 51EC` |
| Key server  | `keys.openpgp.org`                                 |
| Expiry      | 2 years from generation date; rotate before expiry |

**Verify a downloaded artifact**

```bash
# Import the public key (once)
gpg --keyserver keys.openpgp.org --recv-keys 3E333148F633F4749F6A4DF30ABC39374C2B51EC

# Verify the .asc signature against the JAR
gpg --verify finance.idem.core-0.1.0.jar.asc finance.idem.core-0.1.0.jar
# Expected: "Good signature from Idem Finance <flaubert165@gmail.com>"
```

**Key rotation**

The signing key carries a 2-year expiry. Rotation process:
1. Generate a new RSA-4096 key with the same owner identity
2. Cross-certify the new key with the old key before the old key expires
3. Publish both keys to `keys.openpgp.org` and `keyserver.ubuntu.com`
4. Replace `GPG_PRIVATE_KEY` and `GPG_PASSPHRASE` in GitHub Actions secrets
5. Update this README with the new Key ID and fingerprint

### Container images (Cosign)

Docker images published to `ghcr.io/idem-finance/idem` are signed with
[Sigstore Cosign](https://docs.sigstore.dev/cosign/overview/) using keyless signing
via GitHub Actions OIDC. No key pair is required — provenance is verified against
the Rekor transparency log.

**Verify a released image**

```bash
# Install Cosign: https://docs.sigstore.dev/cosign/system_config/installation/
cosign verify \
  --certificate-identity-regexp="https://github.com/idem-finance/idem/.github/workflows/release.yml@refs/tags/" \
  --certificate-oidc-issuer="https://token.actions.githubusercontent.com" \
  ghcr.io/idem-finance/idem:v0.1.0
```

A successful verification prints the signing certificate and confirms the image was
built by this repository's release workflow.

---

## Attribution

If you build a product or service on top of Idem and make it available to others, include a visible acknowledgement in your documentation, "about" screen, or equivalent location — for example:

> Powered by [Idem](https://github.com/idem-finance/idem)

This requirement is part of the FSL-1.1-Apache-2.0 license and applies during the FSL window (the first two years after each release). It does not apply to purely internal deployments.
