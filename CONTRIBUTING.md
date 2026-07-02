# Contributing to Idem

Thanks for your interest in contributing. This document covers the mechanics of getting a change merged. For product/architecture context, see /docs.

---

## Development setup

**Prerequisites:** JDK 21, Maven 3.9+ (the repo ships `./mvnw`, so a local Maven install is optional), Docker.

This works the same on WSL2, macOS, and Linux — the only OS-specific piece is having a working Docker daemon (Docker Desktop on Windows/macOS, native Docker on Linux).

```bash
git clone https://github.com/idem-finance/idem.git
cd idem

# Start PostgreSQL 16 + Redis 7
make up                # or: docker compose up -d

# Build all modules (skips tests for speed)
make build              # or: ./mvnw install -DskipTests

# Seed a dev tenant + print an ADMIN API key
make seed

# Run the app
./mvnw spring-boot:run -pl app -Dspring-boot.run.profiles=dev
```

The API is served at `http://localhost:8081` (`/swagger-ui.html` for interactive docs).

---

## Project structure

Idem is a single-repo **Maven multi-module** project (not Gradle). Each module has one job:

| Module | Responsibility |
|---|---|
| `core` | Pure domain model — zero framework dependencies |
| `application` | Use cases — no HTTP/DB/framework dependencies |
| `infrastructure` | All external adapters (Postgres, Redis, chain readers, security) |
| `api` | REST controllers, OpenAPI spec |
| `mcp` | MCP server tools for agentic execution |
| `sdk-kotlin` | Standalone Kotlin HTTP client, published independently |
| `app` | Spring Boot entry point — wires every other module together |

Full module layout and rationale: [CLAUDE.md § Repository structure](CLAUDE.md#repository-structure).

### Module dependency rules — cannot be violated

```
app  →  api             →  application  →  core
app  →  mcp              →  application  →  core
app  →  infrastructure   →  application  →  core
sdk-kotlin  →  (nothing in this repo)
```

- `core` depends on nothing — no Spring, no JPA, no Kafka.
- `application` depends only on `core` — no Spring, no DB, no HTTP.
- `infrastructure` and `api` depend on `application`, never on each other.
- `app` is the only module allowed to depend on everything.

ArchUnit-based architecture tests (`app/src/test/kotlin/finance/idem/ModularityTest.kt`) enforce these boundaries at test time and **fail the build** on violations — this isn't a style preference, it's a compile-time gate.

---

## Running tests

```bash
./mvnw test                    # all modules
./mvnw test -pl core           # single module
./mvnw test -pl application,core  # multiple modules
./mvnw verify                   # full build + tests + JaCoCo coverage gate
```

`verify` is what CI runs and what's required before opening a PR — it enforces the coverage minimum that plain `test` does not check.

Tests touching PostgreSQL or Redis use Testcontainers, so Docker must be running locally.

Every public function in `core` and `application` needs a unit test. Features that cross an I/O boundary (DB, HTTP, messaging) need an integration test alongside the unit tests — not a substitute for them.

---

## Code style

- Kotlin idioms: data classes, sealed classes, extension functions, `Result<T>` for recoverable errors.
- No nulls as a lazy default in the domain model — `?` only where a field is semantically optional.
- One class per file.
- `core` must compile with zero Spring (or any framework) on the classpath — if it doesn't, that's a bug, not a style nit.

ktlint and Detekt are wired into the build and gate `./mvnw verify` (see `pom.xml`'s `ktlint-check`/`detekt-check` executions). Run `./mvnw antrun:run@ktlint-format` to auto-fix formatting locally before pushing. Detekt violations in pre-existing code are tracked via per-module baseline files (`detekt-baseline-<artifactId>.xml`) — don't add new violations, and don't expand the baseline to hide new ones.

---

## PR process

1. **Open or reference an issue first.** Non-trivial changes should have an issue before a PR exists — this is where design gets discussed, not in PR comments after the fact.
2. **Keep commits atomic.** One logical change per commit. Reference the issue in the commit message: `feat: implement PolicyGuard (#159)`.
3. **Never commit directly to `main`.** Always branch and open a PR.
4. **Before opening the PR**, run `./mvnw clean verify` from the repo root and confirm `BUILD SUCCESS`. A PR on a branch that doesn't compile, has failing tests, or fails the coverage gate won't be reviewed.
5. Update any docs affected by the change (inline KDoc on public interfaces, OpenAPI annotations, README, Bruno collection) in the same PR — not as a follow-up.

---

## Developer Certificate of Origin (DCO)

Every commit must be signed off to certify you have the right to submit the contribution under this project's license:

```bash
git commit -s -m "feat: describe the change"
```

This appends a `Signed-off-by: Your Name <your.email@example.com>` trailer to the commit message. It is a legal attestation, not a GPG signature — see [developercertificate.org](https://developercertificate.org) for the full text of what you're certifying.

PRs are checked automatically; a PR with any unsigned commit will fail the DCO check and cannot be merged. If you forget `-s` on a commit, amend it (`git commit --amend -s`) or add a follow-up signed-off commit — see the PR template checklist.
