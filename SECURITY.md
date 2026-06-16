# Security Policy

## Supported Versions

Idem is currently pre-1.0. Security fixes are applied to the latest commit on `main` only.

| Version | Supported |
|---------|-----------|
| `main` (latest) | ✅ |
| Older snapshots | ❌ |

Once versioned releases are published, this table will be updated to reflect the support window.

---

## Reporting a Vulnerability

**Do not open a public GitHub issue for security vulnerabilities.**

### Option 1 — Email (preferred)

Send a report to **italo@idem.finance** with the subject line `[SECURITY] <brief description>`.

Include:
- Description of the vulnerability and the affected component
- Steps to reproduce or a minimal proof-of-concept
- Version or commit SHA where the issue was observed
- Your assessment of impact and severity (CVSS score welcome but not required)

### Option 2 — GitHub Private Vulnerability Reporting

Use GitHub's built-in private advisory flow:
[Report a vulnerability](https://github.com/idem-finance/idem/security/advisories/new)

Reports submitted here are visible only to maintainers and GitHub Security.

---

## Response Timeline

| Milestone | Target |
|-----------|--------|
| Acknowledgement | 48 hours |
| Triage and initial assessment | 5 business days |
| Fix and coordinated disclosure | 90 days |

For complex issues requiring more time, we will notify the reporter proactively and agree on an extended timeline before any public disclosure.

---

## Disclosure Policy

We follow **coordinated disclosure**:

1. Reporter submits the vulnerability privately.
2. Maintainers confirm, assess, and develop a fix.
3. A patch is released and a CVE is requested (if applicable).
4. A public security advisory is published after the fix is available.

We ask that reporters keep the vulnerability confidential until a fix has been released. We commit to crediting reporters in the advisory unless they prefer to remain anonymous.

---

## Out of Scope

The following are **not** in scope for this policy:

- Vulnerabilities in third-party dependencies — please report those to the upstream project
- Social engineering, phishing, or physical attacks
- Denial-of-service via resource exhaustion without a meaningful exploit path
- Infrastructure issues in `idem-infra` (private repo, managed separately)
- Issues that require physical access to a device

---

## Safe Harbor

Idem will not pursue legal action against researchers who:

- Report vulnerabilities in good faith following this policy
- Avoid accessing, modifying, or deleting user data beyond what is necessary to demonstrate the issue
- Do not exploit the vulnerability beyond what is needed to confirm its existence
- Do not disclose the vulnerability publicly before a fix is available

We consider good-faith security research to be a valuable contribution to the project.
