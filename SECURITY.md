# Security Policy

## Supported versions

Security fixes are issued against the latest minor release line on Maven Central. While codeiq is pre-1.0 (`0.x.y`) only the **latest** released `0.MINOR.x` line receives backports; older minor lines are EOL the moment a new minor ships.

| Version line | Status |
|---|---|
| `0.1.x` | Supported (current) |
| `< 0.1.0` | Unsupported |

`-SNAPSHOT` builds are development snapshots; they do not receive security fixes by themselves — you should be tracking the latest tagged release.

## Reporting a vulnerability

Please **do not open a public GitHub issue** for security problems.

Use one of:

- **GitHub private vulnerability report** — preferred. Open `https://github.com/RandomCodeSpace/codeiq/security/advisories/new` (you must be signed in to GitHub). The advisory channel is monitored by the maintainer.
- **Email** — `ak.nitrr13@gmail.com`. Put `[codeiq security]` in the subject so the report is triaged ahead of normal mail.

Please include:

- The codeiq version (`java -jar code-iq-*-cli.jar version` or `pom.xml` coordinate).
- The shortest reproducer you can produce — a CLI command or test case is ideal.
- Your assessment of impact (e.g., RCE, path traversal, info-disclosure, DoS).
- Whether the issue is in a transitive dependency (please name the dependency + advisory ID if known).

## What you can expect

- **Acknowledgement** within 72 hours.
- **Initial triage** within 7 days, with a severity rating (CVSS v3.1) and an indicative remediation timeline.
- **Coordinated disclosure** — we will agree on a public-disclosure date with the reporter; default is 90 days from triage, sooner for low-impact / already-public issues.
- **Credit** in the GHSA advisory and `CHANGELOG.md` (unless the reporter requests anonymity).

We do not currently run a paid bug bounty.

## Scope

In-scope:

- The codeiq CLI (`code-iq-*-cli.jar`).
- The library JAR (`io.github.randomcodespace.iq:code-iq`).
- The bundled REST API + MCP server (`serve` subcommand) — including path traversal, authn/authz, deserialisation, request smuggling, and SSRF.
- The bundled React UI assets shipped inside the JAR.
- The pipeline cache (H2) and graph store (Neo4j Embedded) — including local privilege escalation and data tampering.

Out of scope:

- Vulnerabilities that require pre-existing local code execution on the developer's machine (we ship as a developer tool — by definition you trust the code you point it at).
- Public-internet attack surface — codeiq does not expose any service to the public internet by default; deploying the `serve` endpoint behind hostile reverse-proxies is out of scope.
- Findings in third-party services we do not control (Maven Central, GitHub itself, SonarCloud, etc.) — please report those upstream.

## Hardening references

- [`shared/runbooks/engineering-standards.md`](shared/runbooks/engineering-standards.md) — CVE policy and quality gates.
- [`shared/runbooks/rollback.md`](shared/runbooks/rollback.md) §6 — secret rotation flow.
- `.github/workflows/scorecard.yml` — OpenSSF Scorecard supply-chain checks.
- `.github/workflows/codeql.yml` — code scanning (SARIF in the Security tab).
- `.github/dependabot.yml` — automated dependency / GHA / Docker bumps.

## Changelog

This file is versioned as part of the repo. Material changes (e.g., raising the supported-versions table, changing the disclosure timeline) are announced via a Release note and a Paperclip board comment.
