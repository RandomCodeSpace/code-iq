<p align="center">
  <h1 align="center">codeiq</h1>
  <p align="center">
    <strong>Deterministic code knowledge graph — scans codebases to map services, endpoints, entities, infrastructure, auth patterns, and framework usage. No AI, pure static analysis. Single static Go binary; MCP server included.</strong>
  </p>
</p>

<p align="center">
  <a href="https://github.com/RandomCodeSpace/codeiq/releases/latest"><img src="https://img.shields.io/github/v/release/RandomCodeSpace/codeiq?style=flat-square&logo=go&label=Release" alt="Latest release"></a>
  <a href="https://github.com/RandomCodeSpace/codeiq/actions/workflows/go-ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/RandomCodeSpace/codeiq/go-ci.yml?branch=main&style=flat-square&logo=github&label=CI" alt="CI"></a>
  <a href="https://go.dev/dl/"><img src="https://img.shields.io/badge/Go-1.25.10-00ADD8?style=flat-square&logo=go&logoColor=white" alt="Go 1.25.10"></a>
  <a href="https://github.com/RandomCodeSpace/codeiq/blob/main/LICENSE"><img src="https://img.shields.io/github/license/RandomCodeSpace/codeiq?style=flat-square&label=License" alt="License"></a>
  <a href="https://github.com/RandomCodeSpace/codeiq/actions/workflows/security.yml"><img src="https://img.shields.io/github/actions/workflow/status/RandomCodeSpace/codeiq/security.yml?branch=main&style=flat-square&logo=github&label=Security%20%28OSS-CLI%29" alt="Security"></a>
  <a href="https://api.securityscorecards.dev/projects/github.com/RandomCodeSpace/codeiq"><img src="https://api.securityscorecards.dev/projects/github.com/RandomCodeSpace/codeiq/badge" alt="OpenSSF Scorecard"></a>
  <a href="https://www.bestpractices.dev/projects/12650"><img src="https://www.bestpractices.dev/projects/12650/badge" alt="OpenSSF Best Practices"></a>
  <a href="https://github.com/RandomCodeSpace/codeiq"><img src="https://img.shields.io/badge/detectors-100-brightgreen?style=flat-square&logo=codefactor&logoColor=white" alt="100 Detectors"></a>
  <a href="https://github.com/RandomCodeSpace/codeiq"><img src="https://img.shields.io/badge/languages-35%2B-blue?style=flat-square&logo=stackblitz&logoColor=white" alt="35+ Languages"></a>
</p>

---

## What it is

codeiq scans a codebase and produces a deterministic graph of its
services, endpoints, entities, infrastructure, auth patterns, and
framework usage. Same input ⇒ same output, every time.

- **Single static binary** — built from the `go/` tree. No JVM, no
  Spring Boot start time. ~30 MB. CGO enabled (Kuzu graph + SQLite
  cache).
- **100 detectors** across 35+ languages — Java, Kotlin, Scala, Python,
  TypeScript/JavaScript, Go, Rust, C#, C++, Terraform, Bicep, Helm,
  Kubernetes, Docker, GitHub Actions, GitLab CI, …
- **MCP server included** — `codeiq mcp` runs an MCP stdio server
  exposing 10 user-facing tools (6 consolidated mode-driven +
  `run_cypher` + `read_file` + `generate_flow` + `review_changes`)
  so Claude / Cursor / any MCP-aware agent can query the graph
  directly.
- **LLM-driven PR review** — `codeiq review` walks the diff, queries
  the indexed graph for evidence, and asks Ollama (Cloud or local) for
  review comments.

## Install

### Pre-built binary

Grab from
[Releases](https://github.com/RandomCodeSpace/codeiq/releases/latest):

```bash
curl -L https://github.com/RandomCodeSpace/codeiq/releases/latest/download/codeiq_$(uname -s | tr A-Z a-z)_$(uname -m | sed s/x86_64/amd64/).tar.gz | tar xz
sudo install codeiq /usr/local/bin/
codeiq --version
```

Verify (Sigstore keyless):

```bash
sha256sum -c checksums.sha256
cosign verify-blob \
  --bundle checksums.sha256.cosign.bundle \
  --certificate-identity-regexp 'https://github.com/RandomCodeSpace/codeiq/.github/workflows/release-go.yml@.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  checksums.sha256
```

### Build from source

Requires Go 1.25.10+ and a C toolchain (CGO).

```bash
git clone https://github.com/RandomCodeSpace/codeiq.git
cd codeiq
CGO_ENABLED=1 go build -o /usr/local/bin/codeiq ./cmd/codeiq
codeiq --version
```

Or directly via `go install`:

```bash
CGO_ENABLED=1 go install github.com/randomcodespace/codeiq/cmd/codeiq@latest
```

## Quickstart

```bash
# Index a repository → SQLite analysis cache.
codeiq index /path/to/repo

# Enrich → Kuzu graph at .codeiq/graph/codeiq.kuzu.
codeiq enrich /path/to/repo

# Query.
codeiq stats /path/to/repo
codeiq find endpoints /path/to/repo
codeiq query consumers <node-id> /path/to/repo
codeiq topology /path/to/repo
codeiq flow /path/to/repo --view overview --format mermaid

# LLM PR review (local Ollama; OLLAMA_API_KEY → Cloud).
codeiq review --base origin/main --head HEAD /path/to/repo
```

## MCP integration

Add to your MCP client config (e.g. `.mcp.json` at the project root):

```json
{
  "mcpServers": {
    "code-mcp": {
      "command": "codeiq",
      "args": ["mcp"]
    }
  }
}
```

Ten user-facing tools: six mode-driven (`graph_summary`,
`find_in_graph`, `inspect_node`, `trace_relationships`,
`analyze_impact`, `topology_view`) plus `run_cypher` (Cypher escape
hatch), `read_file` (utility), `generate_flow`, and `review_changes`.

## CLI reference

| Command | Purpose |
|---|---|
| `index [path]` | Scan files → SQLite analysis cache. |
| `enrich [path]` | Load cache → Kuzu graph; run linkers + layer classifier. |
| `mcp [path]` | Stdio MCP server (Claude / Cursor). |
| `stats [path]` | Categorized statistics. |
| `query <kind> <id> [path]` | consumers / producers / callers / dependencies / dependents / shortest-path / cycles / dead-code. |
| `find <preset> [path]` | endpoints, entities, services, … |
| `cypher <query> [path]` | Raw Cypher against Kuzu (read-only). |
| `flow [path]` | Mermaid / dot / yaml flow diagrams. |
| `graph [path]` | Export graph: json / yaml / mermaid / dot. |
| `topology [path]` | Service-topology projection. |
| `review [path]` | LLM-driven PR review. |
| `cache <action>` | Inspect / clear the analysis cache. |
| `plugins <action>` | List + describe registered detectors. |
| `config <action>` | Validate / explain `codeiq.yml`. |
| `version` | Build info. |

`codeiq <cmd> --help` for full flag listing.

## Design

The graph is canonical and deterministic — `GraphBuilder` deduplicates
nodes by ID (confidence-aware merge) and edges by canonical
`(source, target, kind)` tuple. Phantom edges (endpoint missing from
the graph) are dropped at snapshot. Every run prints a
"Deduped: N nodes, M edges  Dropped: K phantom edges" line so graph
hygiene is visible.

Pipeline: FileDiscovery → tree-sitter / regex → detectors →
GraphBuilder → linkers → LayerClassifier → Kuzu. See
[`CLAUDE.md`](CLAUDE.md) for the full architecture and the detector
authoring contract.

## Releases

Tag `vX.Y.Z` → `.github/workflows/release-go.yml` builds linux/amd64,
linux/arm64, darwin/arm64 archives with SPDX SBOMs (Syft); the
checksum manifest is keyless-signed via Cosign + GitHub OIDC
(Sigstore Rekor). Runbook:
[`shared/runbooks/release-go.md`](shared/runbooks/release-go.md).

## Security

See [SECURITY.md](SECURITY.md). Supply-chain stack: OpenSSF Best
Practices [12650](https://www.bestpractices.dev/projects/12650),
OpenSSF Scorecard, and the OSS-CLI workflow
([`security.yml`](.github/workflows/security.yml)) running OSV-Scanner,
Trivy, Semgrep, Gitleaks, jscpd, and `anchore/sbom-action` on every PR.

## License

See [LICENSE](LICENSE).
