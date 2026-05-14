# Security Policy

## Supported versions

Security fixes are issued against the latest minor release line. While codeiq is pre-1.0 (`0.x.y`) only the **latest** released `0.MINOR.x` line receives backports; older minor lines are EOL the moment a new minor ships.

| Version line | Status |
|---|---|
| `0.3.x` | Supported (current — Go single binary) |
| `0.2.x` and below | Unsupported (Java/Spring Boot reference, deleted at Phase 6 cutover) |

Development builds (untagged `main`) are not covered — track the latest tagged release.

## Reporting a vulnerability

Please **do not open a public GitHub issue** for security problems.

Use one of:

- **GitHub private vulnerability report** — preferred. Open `https://github.com/RandomCodeSpace/codeiq/security/advisories/new` (you must be signed in to GitHub). The advisory channel is monitored by the maintainer.
- **Email** — `ak.nitrr13@gmail.com`. Put `[codeiq security]` in the subject so the report is triaged ahead of normal mail.

Please include:

- The codeiq version (`codeiq --version`).
- The shortest reproducer you can produce — a CLI command, a test case, or an indexed-fixture path.
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

- The `codeiq` CLI binary and every subcommand (`index`, `enrich`, `mcp`, `query`, `find`, `cypher`, `stats`, `flow`, `graph`, `topology`, `review`, `cache`, `plugins`, `config`).
- The stdio MCP server (`codeiq mcp`) — including its 10 user-facing tools (`graph_summary`, `find_in_graph`, `inspect_node`, `trace_relationships`, `analyze_impact`, `topology_view`, `run_cypher`, `read_file`, `generate_flow`, `review_changes`). The mutation gate on `run_cypher` is in-scope — bypassing it to mutate the read-only Kuzu store is a vulnerability.
- The pipeline cache (SQLite, `.codeiq/cache/codeiq.sqlite`) and graph store (Kuzu embedded, `.codeiq/graph/codeiq.kuzu`) — including local privilege escalation and data tampering of the indexed graph.
- File-read sandboxing in `read_file` and `codeiq review` — path traversal out of the indexed root is in-scope.
- The release pipeline — Goreleaser config, signing keys (cosign keyless via OIDC), GitHub Actions workflows under `.github/workflows/`, and the published artifacts (binary tarballs + checksums + cosign bundles).

Out of scope:

- Vulnerabilities that require pre-existing local code execution on the developer's machine (we ship as a developer tool — by definition you trust the code you point it at).
- Public-internet attack surface — codeiq does not expose any service to the public internet. It is a CLI + stdio MCP server only; there is no REST API and no web UI (the Java reference had both; they were deleted in Phase 6 cutover and will not be reintroduced).
- Vulnerabilities in the LLM endpoint used by `codeiq review` (Ollama local or cloud) — those are the LLM vendor's surface area.
- Findings in third-party services we do not control (GitHub itself, OpenSSF, Socket Security, etc.) — please report those upstream.

## Hardening references

- [`shared/runbooks/engineering-standards.md`](shared/runbooks/engineering-standards.md) — CVE policy and quality gates.
- [`shared/runbooks/rollback.md`](shared/runbooks/rollback.md) §6 — secret rotation flow.
- `.github/workflows/scorecard.yml` — OpenSSF Scorecard supply-chain checks.
- `.github/workflows/security.yml` — CodeQL, Semgrep, OSV-Scanner, Trivy, Gitleaks, SBOM, Socket Security on every PR.
- `.github/workflows/perf-gate.yml` — enrich memory regression gate (300 MB ceiling on fixture-multi-lang).
- `.github/dependabot.yml` — automated `gomod` + `github-actions` bumps, grouped per ecosystem.

## Changelog

This file is versioned as part of the repo. Material changes (e.g., raising the supported-versions table, changing the disclosure timeline) are announced via a Release note.
