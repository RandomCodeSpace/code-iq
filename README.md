<div align="center">

# codeiq

### Deterministic code-knowledge-graph CLI + stdio MCP server

**Map a polyglot codebase into a queryable graph. 100 detectors. 35+ languages. Zero AI in the pipeline.**

<br>

<p>
  <a href="https://github.com/RandomCodeSpace/codeiq/releases/latest"><img src="https://img.shields.io/github/v/release/RandomCodeSpace/codeiq?style=for-the-badge&logo=github&logoColor=white&label=Release&color=2ea043" alt="Latest release"></a>
  <a href="https://go.dev/dl/"><img src="https://img.shields.io/badge/Go-1.25.10-00ADD8?style=for-the-badge&logo=go&logoColor=white" alt="Go 1.25.10"></a>
  <a href="https://pkg.go.dev/github.com/randomcodespace/codeiq"><img src="https://img.shields.io/badge/pkg.go.dev-reference-007d9c?style=for-the-badge&logo=go&logoColor=white" alt="pkg.go.dev"></a>
  <a href="https://github.com/RandomCodeSpace/codeiq/blob/main/LICENSE"><img src="https://img.shields.io/github/license/RandomCodeSpace/codeiq?style=for-the-badge&label=License&color=blue" alt="License"></a>
</p>

<p>
  <a href="https://github.com/RandomCodeSpace/codeiq/actions/workflows/go-ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/RandomCodeSpace/codeiq/go-ci.yml?branch=main&style=for-the-badge&logo=githubactions&logoColor=white&label=CI" alt="CI"></a>
  <a href="https://github.com/RandomCodeSpace/codeiq/actions/workflows/perf-gate.yml"><img src="https://img.shields.io/github/actions/workflow/status/RandomCodeSpace/codeiq/perf-gate.yml?branch=main&style=for-the-badge&logo=speedtest&logoColor=white&label=Perf%20Gate" alt="Perf Gate"></a>
  <a href="https://github.com/RandomCodeSpace/codeiq/actions/workflows/security.yml"><img src="https://img.shields.io/github/actions/workflow/status/RandomCodeSpace/codeiq/security.yml?branch=main&style=for-the-badge&logo=snyk&logoColor=white&label=Security" alt="Security"></a>
  <a href="https://github.com/RandomCodeSpace/codeiq/actions/workflows/scorecard.yml"><img src="https://img.shields.io/github/actions/workflow/status/RandomCodeSpace/codeiq/scorecard.yml?branch=main&style=for-the-badge&logo=github&logoColor=white&label=Scorecard" alt="Scorecard CI"></a>
</p>

<p>
  <a href="https://www.bestpractices.dev/projects/12650"><img src="https://img.shields.io/cii/percentage/12650?style=for-the-badge&label=OpenSSF%20Best%20Practices&color=4c1" alt="OpenSSF Best Practices"></a>
  <a href="https://scorecard.dev/viewer/?uri=github.com/RandomCodeSpace/codeiq"><img src="https://img.shields.io/ossf-scorecard/github.com/RandomCodeSpace/codeiq?style=for-the-badge&label=OpenSSF%20Scorecard" alt="OpenSSF Scorecard"></a>
  <a href="https://www.sigstore.dev/"><img src="https://img.shields.io/badge/Sigstore-keyless-fbb03b?style=for-the-badge&logo=sigstore&logoColor=white" alt="Sigstore keyless"></a>
  <a href="https://slsa.dev/"><img src="https://img.shields.io/badge/SLSA-Build%20Provenance-2d6cdf?style=for-the-badge&logoColor=white" alt="SLSA Build Provenance"></a>
</p>

<p>
  <img src="https://img.shields.io/badge/Detectors-100-brightgreen?style=for-the-badge&logo=codefactor&logoColor=white" alt="100 Detectors">
  <img src="https://img.shields.io/badge/Languages-35%2B-blue?style=for-the-badge&logo=stackblitz&logoColor=white" alt="35+ Languages">
  <img src="https://img.shields.io/badge/Tests-880%2B-success?style=for-the-badge&logo=go&logoColor=white" alt="880+ Tests">
  <img src="https://img.shields.io/badge/MCP-stdio-7c3aed?style=for-the-badge&logo=anthropic&logoColor=white" alt="MCP stdio">
  <img src="https://img.shields.io/badge/Kuzu-0.11.3-f97316?style=for-the-badge&logoColor=white" alt="Kuzu 0.11.3">
  <img src="https://img.shields.io/badge/CGO-required-yellow?style=for-the-badge&logoColor=white" alt="CGO required">
</p>

<br>

<sup>
  <b>Note on SonarQube:</b> codeiq deliberately uses an in-house OSS-CLI security stack (CodeQL, Semgrep, OSV-Scanner, Trivy, Gitleaks, jscpd, govulncheck) instead of Sonar &mdash; see <a href="docs/07-integrations.md"><code>docs/07-integrations.md</code></a> &amp; <a href=".github/workflows/security.yml"><code>security.yml</code></a>.
</sup>

</div>

---

## Table of contents

- [Why codeiq](#why-codeiq)
- [How it works](#how-it-works)
- [Install](#install)
- [Quickstart](#quickstart)
- [MCP integration](#mcp-integration)
- [CLI cheatsheet](#cli-cheatsheet)
- [Architecture at a glance](#architecture-at-a-glance)
- [Verification](#verification-supply-chain)
- [Documentation](#documentation)
- [Project status](#project-status)
- [Contributing](#contributing)
- [License](#license)

---

## Why codeiq

<table>
<tr>
<td valign="top" width="33%">

### Deterministic

Same input → same output, byte-for-byte. Detector emissions are confidence-tagged (`LEXICAL` / `SYNTACTIC` / `RESOLVED`); the graph builder dedup-merges with confidence-aware property union and drops phantom edges at snapshot. Every detector ships a determinism test.

</td>
<td valign="top" width="33%">

### Agent-ready

Stdio MCP server with 10 read-only tools wired for Claude Code / Cursor / Cline. Mode-driven surface (`graph_summary`, `find_in_graph`, `inspect_node`, `trace_relationships`, `analyze_impact`, `topology_view`) plus `run_cypher` for the power users.

</td>
<td valign="top" width="33%">

### Supply-chain hardened

Goreleaser + Cosign keyless via GitHub OIDC + Sigstore Rekor transparency log + Syft SPDX SBOMs + SLSA build provenance attestation + OpenSSF Scorecard + 6 OSS-CLI security scanners in CI.

</td>
</tr>
<tr>
<td valign="top" width="33%">

### Polyglot

100 detectors across **35+ languages**: Java, Kotlin, Scala, Python, TypeScript, JavaScript, Go, Rust, C#, C++, plus IaC (Terraform, Bicep, Helm, Kubernetes, Docker, CloudFormation), config (YAML/JSON/TOML/INI), SQL, protobuf, shell, and more.

</td>
<td valign="top" width="33%">

### No AI in the pipeline

Index + enrich + every MCP query is pure static analysis. The only LLM touch is the opt-in `codeiq review` subcommand. No telemetry. No auto-update. No outbound network during core flows.

</td>
<td valign="top" width="33%">

### Single static binary

~25 MB. CGO embeds Kuzu (graph) + SQLite (cache) + tree-sitter (parser). No daemons. No external services. Works behind corporate firewalls / air-gapped after the initial install.

</td>
</tr>
</table>

---

## How it works

```
   source                                                         ┌─────────────┐
   tree   ─►  index ──────►  ┌──────────┐  ──►  enrich ──────►   │   Kuzu      │
              FileDiscovery  │  SQLite  │       linkers +         │   graph     │
              tree-sitter    │   cache  │       layer classify    │  (FTS-idx)  │
              100 detectors  │          │       intelligence      │             │
              dedup + sort   └──────────┘       ServiceDetector   └──────┬──────┘
                                                bulk COPY → Kuzu         │
                                                                         ▼
                              ┌───────────────────────────────────────────────┐
                              │  Read-only consumers (all powered by Kuzu):   │
                              │    stats, find, query, cypher, flow, graph,   │
                              │    topology, review (+ Ollama LLM)            │
                              │    mcp (stdio JSON-RPC, 10 tools)             │
                              └───────────────────────────────────────────────┘
```

Three commands cover the lifecycle:

| Step | Command | What lands |
|---|---|---|
| **1.** Index | `codeiq index <path>` | `<path>/.codeiq/cache/codeiq.sqlite` (content-hash keyed; resumable) |
| **2.** Enrich | `codeiq enrich <path>` | `<path>/.codeiq/graph/codeiq.kuzu/` + BM25 FTS indexes |
| **3.** Query | `codeiq mcp \| stats \| find \| query \| cypher \| ...` | Read-only consumers of the Kuzu store |

See [`docs/04-main-flows.md`](docs/04-main-flows.md) for per-flow entry points + failure modes.

---

## Install

### Pre-built binary (Linux amd64 / arm64, macOS arm64)

```bash
# Pick your platform; replace if needed
curl -L https://github.com/RandomCodeSpace/codeiq/releases/latest/download/codeiq_$(uname -s | tr A-Z a-z)_$(uname -m | sed s/x86_64/amd64/).tar.gz | tar xz
sudo install codeiq /usr/local/bin/
codeiq --version
```

### `go install`

```bash
CGO_ENABLED=1 go install github.com/randomcodespace/codeiq/cmd/codeiq@latest
```

> **Requires** Go 1.25.0+ and a C/C++ toolchain (Kuzu, SQLite, and tree-sitter all need CGO).

### Build from source

```bash
git clone https://github.com/RandomCodeSpace/codeiq.git
cd codeiq
CGO_ENABLED=1 go build -o /usr/local/bin/codeiq ./cmd/codeiq
codeiq --version
```

Full setup checklist in [`docs/01-local-setup.md`](docs/01-local-setup.md).

---

## Quickstart

```bash
# 1. Scan files → SQLite cache
codeiq index /path/to/repo

# 2. Load cache → Kuzu graph + FTS indexes
codeiq enrich /path/to/repo

# 3. Ask questions
codeiq stats        /path/to/repo
codeiq find         endpoints /path/to/repo
codeiq query        consumers <node-id> /path/to/repo
codeiq topology     /path/to/repo
codeiq flow         overview /path/to/repo --format mermaid

# 4. Wire into your AI agent (Claude Code / Cursor / Cline)
codeiq mcp          /path/to/repo

# 5. Get an LLM-driven PR review (local Ollama by default)
codeiq review       /path/to/repo --base origin/main --head HEAD
```

---

## MCP integration

Add to your MCP client config (`.mcp.json` at the repo root, or your editor's MCP settings):

```json
{
  "mcpServers": {
    "codeiq": {
      "command": "codeiq",
      "args": ["mcp", "/path/to/repo"]
    }
  }
}
```

<details>
<summary><b>Ten user-facing tools</b></summary>

| Tool | Modes |
|---|---|
| `graph_summary` | `overview` / `categories` / `capabilities` / `provenance` |
| `find_in_graph` | `nodes` / `edges` / `text` / `fuzzy` / `by_file` / `by_endpoint` |
| `inspect_node` | `neighbors` / `ego` / `evidence` / `source` |
| `trace_relationships` | `callers` / `consumers` / `producers` / `dependencies` / `dependents` / `shortest_path` |
| `analyze_impact` | `blast_radius` / `trace` / `cycles` / `circular_deps` / `dead_code` / `dead_services` / `bottlenecks` |
| `topology_view` | `summary` / `service` / `service_deps` / `service_dependents` / `flow` |
| `run_cypher` | Read-only Cypher escape hatch; mutation gate enforced |
| `read_file` | Path-sandboxed source reader (full file or line range) |
| `generate_flow` | Architecture flow diagrams (mermaid / dot / yaml) — 5 views |
| `review_changes` | LLM-driven git-diff review against the graph (Ollama) |

</details>

---

## CLI cheatsheet

<details>
<summary><b>Click to expand</b></summary>

| Command | Purpose |
|---|---|
| `index [path]` | Scan files → SQLite analysis cache |
| `enrich [path]` | Load cache → Kuzu graph + build FTS indexes |
| `mcp [path]` | Stdio MCP server for Claude Code / Cursor |
| `stats [path]` | Categorized statistics (graph / languages / frameworks / infra / connections / auth / architecture) |
| `query <kind> <id> [path]` | `consumers` / `producers` / `callers` / `dependencies` / `dependents` |
| `find <preset> [path]` | `endpoints` / `guards` / `entities` / `topics` / `queues` / `services` / `databases` / `components` |
| `cypher <query> [path]` | Read-only Cypher against Kuzu |
| `flow <view> [path]` | Architecture diagrams — `overview` / `ci` / `deploy` / `runtime` / `auth` |
| `graph [path]` | Export full graph as json / yaml / mermaid / dot |
| `topology <sub> [path]` | Service topology + `service-detail` / `blast-radius` / `bottlenecks` / `circular` / `dead` / `path` |
| `review [path]` | LLM-driven PR review (Ollama local by default; cloud via `OLLAMA_API_KEY`) |
| `cache <action>` | Inspect / list / inspect-row / clear the SQLite cache |
| `plugins <action>` | List + inspect registered detectors |
| `version` | Build info (version, commit, date, Go toolchain, platform, features) |

Run `codeiq <cmd> --help` for full flag listings. Full reference in [`docs/05-configuration.md`](docs/05-configuration.md).

</details>

---

## Architecture at a glance

```
codeiq/
├── cmd/codeiq/main.go      ── 5-line entry shim
├── internal/
│   ├── analyzer/           ── index + enrich pipelines + GraphBuilder + ServiceDetector
│   ├── cache/              ── SQLite cache (WAL, content-hash keyed, 5 tables)
│   ├── cli/                ── cobra subcommands + detectors_register.go (choke point)
│   ├── detector/           ── 100 detectors organized by family
│   │   ├── jvm/{java,kotlin,scala}/   python/   typescript/   golang/
│   │   ├── frontend/  csharp/  systems/{cpp,rust}/  iac/  structured/
│   │   ├── auth/  proto/  sql/  markup/  script/shell/  generic/
│   │   └── base/           ── shared helpers (NOT detectors)
│   ├── flow/               ── architecture-flow diagram engine
│   ├── graph/              ── Kuzu facade + FTS + mutation gate
│   ├── intelligence/       ── Lexical enricher + per-language extractors
│   ├── mcp/                ── 10 MCP tools (stdio JSON-RPC)
│   ├── model/              ── CodeNode / CodeEdge / NodeKind (34) / EdgeKind (28) / Confidence / Layer
│   ├── parser/             ── tree-sitter + structured parsers
│   ├── query/              ── service / topology / stats / dead-code Cypher templates
│   └── review/             ── PR-review pipeline (diff + Ollama)
├── parity/                 ── parity harness (build tag `parity`)
├── testdata/               ── fixture-minimal + fixture-multi-lang
├── .github/workflows/      ── go-ci, perf-gate, release-go, release-darwin, security, scorecard
└── .goreleaser.yml         ── Goreleaser v2 (CGO multi-arch + Cosign + Syft)
```

Deep dive in [`docs/02-architecture.md`](docs/02-architecture.md) and [`docs/03-code-map.md`](docs/03-code-map.md).

---

## Verification (supply chain)

Every release artifact is keyless-signed via Cosign + GitHub OIDC and recorded in the Sigstore Rekor transparency log. SLSA build provenance attestations land in GitHub's attestations store.

### Verify the checksum manifest signature

```bash
cosign verify-blob \
  --bundle checksums.sha256.cosign.bundle \
  --certificate-identity-regexp 'https://github.com/RandomCodeSpace/codeiq/.github/workflows/release-go.yml@.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  checksums.sha256
```

### Verify the darwin tarball (signed separately)

```bash
cosign verify-blob \
  --bundle codeiq_0.4.1_darwin_arm64.tar.gz.cosign.bundle \
  --certificate-identity-regexp 'https://github.com/RandomCodeSpace/codeiq/.github/workflows/release-darwin.yml@.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  codeiq_0.4.1_darwin_arm64.tar.gz
```

### Verify the SLSA build provenance

```bash
gh attestation verify codeiq_0.4.1_linux_amd64.tar.gz --owner RandomCodeSpace
```

---

## Documentation

<table>
<tr>
<th align="left">Starter pack</th>
<th align="left">Reference</th>
<th align="left">Operate</th>
</tr>
<tr>
<td valign="top">

[Project overview](docs/00-project-overview.md)<br>
[Local setup](docs/01-local-setup.md)<br>
[Architecture](docs/02-architecture.md)<br>
[Main flows](docs/04-main-flows.md)

</td>
<td valign="top">

[Code map](docs/03-code-map.md)<br>
[Configuration](docs/05-configuration.md)<br>
[Data model](docs/06-data-model.md)<br>
[Integrations](docs/07-integrations.md)

</td>
<td valign="top">

[Testing](docs/08-testing.md)<br>
[Build / deploy / release](docs/09-build-deploy-release.md)<br>
[Known risks + TODOs](docs/10-known-risks-and-todos.md)<br>
[Agent handoff](docs/11-agent-handoff.md)

</td>
</tr>
</table>

Architectural decisions: [`docs/adr/`](docs/adr/). Repo-specific Claude Code instructions: [`CLAUDE.md`](CLAUDE.md).

---

## Project status

| Surface | State |
|---|---|
| CLI core (`index` / `enrich` / `stats` / `find` / `query` / `cypher`) | Production |
| MCP stdio server (10 tools) | Production |
| Kuzu 0.11.3 + native FTS (BM25) | Production |
| Goreleaser pipeline + Cosign keyless | Production |
| 884+ tests passing (race + vet + staticcheck + gosec + govulncheck on every PR) | Production |
| `codeiq review` (LLM PR review) | Beta — works end-to-end against local Ollama |
| `parity/` harness | Idle (Java→Go port artifact; build-tag gated) |

Currently on **v0.4.1**. Release history was reset at v0.4.0 — see [`docs/00-project-overview.md`](docs/00-project-overview.md) for context.

---

## Contributing

- **Branch off `main`.** Conventional-commit subjects (`feat:`, `fix:`, `chore:`, `refactor:`, `test:`, `docs:`, `perf:`).
- **One logical change per commit.** Squash-merge only.
- **Tests + race + vet must pass.** `CGO_ENABLED=1 go test ./... -race -count=1`.
- **Determinism is non-negotiable.** Every new detector ships positive / negative / determinism tests.
- **Read-only MCP.** Tool calls never mutate the graph. Index/enrich happen via the CLI.
- New detector? Don't forget to blank-import it in [`internal/cli/detectors_register.go`](internal/cli/detectors_register.go) — see [`CLAUDE.md`](CLAUDE.md) for the full how-to.

Security: please report privately via [GitHub Security Advisories](https://github.com/RandomCodeSpace/codeiq/security/advisories/new).

---

## License

<a href="LICENSE"><img src="https://img.shields.io/github/license/RandomCodeSpace/codeiq?style=for-the-badge&label=MIT&color=blue" alt="MIT License"></a>

<sub>Copyright © codeiq contributors. See [`LICENSE`](LICENSE).</sub>
