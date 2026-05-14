# codeiq

**Deterministic code-knowledge-graph CLI + stdio MCP server. 100 detectors, 35+ languages. Pure static analysis — no AI in the index/enrich pipeline; LLM use is opt-in for PR review.**

<p align="center">
  <a href="https://github.com/RandomCodeSpace/codeiq/releases/latest"><img src="https://img.shields.io/github/v/release/RandomCodeSpace/codeiq?style=for-the-badge&logo=go&logoColor=white&label=Release" alt="Latest release"></a>
  <a href="https://github.com/RandomCodeSpace/codeiq/actions/workflows/go-ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/RandomCodeSpace/codeiq/go-ci.yml?branch=main&style=for-the-badge&logo=github&logoColor=white&label=CI" alt="CI"></a>
  <a href="https://github.com/RandomCodeSpace/codeiq/actions/workflows/security.yml"><img src="https://img.shields.io/github/actions/workflow/status/RandomCodeSpace/codeiq/security.yml?branch=main&style=for-the-badge&logo=github&logoColor=white&label=Security" alt="Security"></a>
  <a href="https://go.dev/dl/"><img src="https://img.shields.io/badge/Go-1.25.10-00ADD8?style=for-the-badge&logo=go&logoColor=white" alt="Go 1.25.10"></a>
  <a href="https://github.com/RandomCodeSpace/codeiq/blob/main/LICENSE"><img src="https://img.shields.io/github/license/RandomCodeSpace/codeiq?style=for-the-badge&logoColor=white&label=License" alt="License"></a>
  <img src="https://img.shields.io/badge/Detectors-100-brightgreen?style=for-the-badge&logo=codefactor&logoColor=white" alt="100 Detectors">
  <img src="https://img.shields.io/badge/Languages-35%2B-blue?style=for-the-badge&logo=stackblitz&logoColor=white" alt="35+ Languages">
  <img src="https://img.shields.io/badge/MCP-Stdio-purple?style=for-the-badge&logo=anthropic&logoColor=white" alt="MCP Stdio">
  <img src="https://img.shields.io/badge/Kuzu-0.11.3-orange?style=for-the-badge&logoColor=white" alt="Kuzu 0.11.3">
</p>

codeiq scans a codebase, builds a deterministic graph of services / endpoints / entities / infra / auth / framework usage, and exposes it via:

- a CLI (`codeiq index → enrich → query/stats/find/cypher/topology/flow`)
- a stdio MCP server (10 read-only tools for Claude Code / Cursor)
- an LLM PR review (`codeiq review`, default backend Ollama local; cloud via `OLLAMA_API_KEY`)

Same input ⇒ same output, every time. Detector emissions are confidence-tagged (`LEXICAL` / `SYNTACTIC` / `RESOLVED`); the graph builder dedup-merges with confidence-aware property union and drops phantom edges at snapshot.

## Install

### Pre-built (Linux / macOS)

```bash
curl -L https://github.com/RandomCodeSpace/codeiq/releases/latest/download/codeiq_$(uname -s | tr A-Z a-z)_$(uname -m | sed s/x86_64/amd64/).tar.gz | tar xz
sudo install codeiq /usr/local/bin/
codeiq --version
```

Cosign keyless verification:
```bash
cosign verify-blob \
  --bundle checksums.sha256.cosign.bundle \
  --certificate-identity-regexp 'https://github.com/RandomCodeSpace/codeiq/.github/workflows/release-go.yml@.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  checksums.sha256
```

### From source (Go 1.25.0+ with CGO toolchain)

```bash
CGO_ENABLED=1 go install github.com/randomcodespace/codeiq/cmd/codeiq@latest
```

Or:
```bash
git clone https://github.com/RandomCodeSpace/codeiq.git
cd codeiq
CGO_ENABLED=1 go build -o /usr/local/bin/codeiq ./cmd/codeiq
```

## Quickstart

```bash
codeiq index   /path/to/repo    # scan → SQLite cache (.codeiq/cache/codeiq.sqlite)
codeiq enrich  /path/to/repo    # load cache → Kuzu graph (.codeiq/graph/codeiq.kuzu) + build FTS indexes
codeiq stats   /path/to/repo
codeiq find    endpoints /path/to/repo
codeiq query   consumers <node-id> /path/to/repo
codeiq topology /path/to/repo
codeiq flow    overview /path/to/repo --format mermaid
codeiq mcp     /path/to/repo    # stdio MCP server (for Claude Code / Cursor)
codeiq review  /path/to/repo --base origin/main --head HEAD    # local Ollama
```

## MCP integration

Add to your MCP client config (`.mcp.json`):

```json
{
  "mcpServers": {
    "code-mcp": {
      "command": "codeiq",
      "args": ["mcp", "/path/to/repo"]
    }
  }
}
```

Ten user-facing tools: six mode-driven (`graph_summary`, `find_in_graph`, `inspect_node`, `trace_relationships`, `analyze_impact`, `topology_view`) plus `run_cypher` (read-only Cypher escape hatch), `read_file`, `generate_flow`, `review_changes`.

## Documentation

| File | Topic |
|---|---|
| [`docs/00-project-overview.md`](docs/00-project-overview.md) | What it is, who it's for, current status |
| [`docs/01-local-setup.md`](docs/01-local-setup.md) | Prereqs, build, test, common issues |
| [`docs/02-architecture.md`](docs/02-architecture.md) | Components, data flow, tradeoffs |
| [`docs/03-code-map.md`](docs/03-code-map.md) | Directory-by-directory tour |
| [`docs/04-main-flows.md`](docs/04-main-flows.md) | index / enrich / mcp / review lifecycles |
| [`docs/05-configuration.md`](docs/05-configuration.md) | env vars, `codeiq.yml`, CLI flags |
| [`docs/06-data-model.md`](docs/06-data-model.md) | Kuzu + SQLite schemas, NodeKind/EdgeKind taxonomy |
| [`docs/07-integrations.md`](docs/07-integrations.md) | External systems (Ollama, GitHub OIDC, Sigstore) |
| [`docs/08-testing.md`](docs/08-testing.md) | Test strategy, fixtures, perf-gate |
| [`docs/09-build-deploy-release.md`](docs/09-build-deploy-release.md) | Goreleaser, CI, supply-chain |
| [`docs/10-known-risks-and-todos.md`](docs/10-known-risks-and-todos.md) | Gotchas, debt, security-sensitive areas |
| [`docs/11-agent-handoff.md`](docs/11-agent-handoff.md) | One-stop brief for future AI agents |
| [`docs/adr/0001-current-architecture.md`](docs/adr/0001-current-architecture.md) | Why the architecture is what it is |
| [`CLAUDE.md`](CLAUDE.md) | Repo-specific instructions for Claude Code |

## License

[MIT](LICENSE)
