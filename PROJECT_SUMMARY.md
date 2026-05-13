# Project Summary: codeiq

> Refreshed 2026-05-13 after Phase 6 cutover (v0.3.0). Audience: AI
> agents (and humans) who need to understand and modify this codebase.
>
> **Canonical depth lives in [`CLAUDE.md`](CLAUDE.md)** (~16 KB,
> agent-oriented, hand-maintained). This file is a thin entry point
> that links into `CLAUDE.md`, the runbooks under
> [`shared/runbooks/`](shared/runbooks/), and the deep-dives under
> [`docs/project/`](docs/project/).

## Identity

- **What it is**: a CLI + MCP server that scans a codebase and emits a
  deterministic code knowledge graph — services, endpoints, entities,
  infrastructure, auth patterns, framework usage. No AI, pure static
  analysis. LLM is opt-in via `codeiq review`.
- **Type**: CLI tool + MCP stdio server, single static binary.
- **Status**: v0.3.0 (Phase 6 cutover landed 2026-05-13). Active.
- **Primary language**: Go 1.25.10. CGO required.

## Tech stack

- **Go 1.25.10** — toolchain pin in `go/go.mod` (module min 1.25.0,
  clamped by `modelcontextprotocol/go-sdk`).
- **Kuzu 0.7.1** (`github.com/kuzudb/go-kuzu`) — embedded graph DB.
- **`mattn/go-sqlite3` 1.14.22** — SQLite analysis cache.
- **`smacker/go-tree-sitter`** — AST parsing (Java / Python / TS / Go).
- **`modelcontextprotocol/go-sdk` v1.6** — stdio MCP server.
- **`spf13/cobra`** — CLI framework.
- Manifest files read: `go/go.mod`, `go/go.sum`.

## Entry points

| Entrypoint | File | Purpose |
|---|---|---|
| CLI / MCP server | `go/cmd/codeiq/main.go` | The only binary. All subcommands live in `internal/cli`. |
| Subcommand registry | `internal/cli/root.go` | Sets up cobra root + registers per-subcommand inits. |
| Detector registry | `internal/cli/detectors_register.go` | Blank-imports every detector package leaf. **Choke point** — forget it and detectors silently no-op. |
| Stdio MCP | `internal/cli/mcp.go` + `internal/mcp/server.go` | Wires consolidated tools + the deprecated 34 + `review_changes`. |
| Analyzer pipeline | `internal/analyzer/analyzer.go` | FileDiscovery → parser → detectors (pool) → GraphBuilder → SQLite. |
| Enrich pipeline | `internal/analyzer/enrich.go` | SQLite → Kuzu + linkers + layer classifier + intelligence. |

## Directory map

```
codeiq/
├── go/                              — Go module (will move to repo root post-v1)
│   ├── cmd/codeiq/                  — main package
│   ├── internal/
│   │   ├── analyzer/                — pipeline orchestration + linkers
│   │   ├── buildinfo/               — version metadata
│   │   ├── cache/                   — SQLite analysis cache
│   │   ├── cli/                     — cobra subcommands
│   │   ├── detector/                — 100 detectors organized by category
│   │   ├── flow/                    — architecture-flow diagram engine
│   │   ├── graph/                   — Kuzu facade (read-only on serve path)
│   │   ├── intelligence/            — lexical + language extractors + evidence + planner
│   │   ├── mcp/                     — MCP server + tool definitions
│   │   ├── model/                   — CodeNode, CodeEdge, kinds, Confidence
│   │   ├── parser/                  — tree-sitter + structured parsers
│   │   ├── query/                   — service / topology / stats
│   │   └── review/                  — PR-review pipeline (diff + Ollama)
│   ├── parity/                      — parity harness (build tag `parity`)
│   ├── testdata/                    — fixtures (fixture-minimal, fixture-multi-lang)
│   ├── go.mod
│   └── go.sum
├── .github/workflows/               — go-ci, perf-gate, release-go, security, scorecard
├── docs/project/                    — architecture + conventions + flows deep-dives
├── shared/runbooks/                 — release-go.md + engineering-standards.md
├── CHANGELOG.md
├── CLAUDE.md                        — SSoT internals doc
├── PROJECT_SUMMARY.md               — this file
├── README.md                        — user-facing entry doc
├── SECURITY.md
└── .goreleaser.yml                  — Goreleaser config (CGO multi-arch)
```

## Run, build, test

Commands taken from `go/go.mod`, `Makefile` (none — pure `go` tooling),
and `.github/workflows/go-ci.yml`:

```bash
# Install deps (vendored via go module cache; no extra step)
cd go

# Run unit tests
CGO_ENABLED=1 go test ./... -count=1

# Race detector
CGO_ENABLED=1 go test ./... -race -count=1

# Static analysis (mirrors CI)
go install honnef.co/go/tools/cmd/staticcheck@2025.1.1
staticcheck ./...
go install github.com/securego/gosec/v2/cmd/gosec@v2.22.0
gosec -quiet -exclude=G104,G115,G202,G204,G301,G304,G306,G401,G404,G501 ./...
go install golang.org/x/vuln/cmd/govulncheck@latest
govulncheck ./...

# Build (local)
CGO_ENABLED=1 go build -o /usr/local/bin/codeiq ./cmd/codeiq
```

**Required env / external services**: none for build. At run-time the
binary reads `OLLAMA_API_KEY` (optional) and `HOMEBREW_TAP_GITHUB_TOKEN`
(release-side only).

## Conventions an agent must respect

- **Detector blank-import**: new package under `internal/detector/<dir>/`
  must be added to `internal/cli/detectors_register.go`. The polyglot
  benchmark caught 15 missing imports (commit `04098be`).
- **Determinism**: never iterate a Go `map` without sorting keys. Run
  the determinism test twice with the same fixture and assert byte
  equality.
- **Anchor nodes for cross-file edges**: use
  `base.EnsureFileAnchor` + `base.EnsureExternalAnchor`. Otherwise
  imports/depends_on edges drop at Snapshot's phantom filter.
- **Read-only MCP**: every MCP tool reads. `run_cypher` rejects
  mutation keywords. `review_changes` reads the graph + shells `git`
  read-only.
- **Confidence + Source mandatory**: every emitted `CodeNode` and
  `CodeEdge`. Base classes stamp the floor at the orchestration
  boundary; detectors override only when they have higher-confidence
  evidence.

Full set in [`CLAUDE.md` §Code Conventions](CLAUDE.md#code-conventions)
and [`docs/project/conventions.md`](docs/project/conventions.md).

## Gotchas

- **Kuzu v0.7.1 binder limitations** — no FTS, no parameterized
  LIMIT/SKIP, `lower()` not `toLower()`, no negative lookahead, list
  comprehensions reject out-of-scope variables. See
  [`CLAUDE.md` §Kuzu v0.7.1 quirks](CLAUDE.md#kuzu-v071-quirks).
- **Go RE2 vs Java regex** — no lookahead, no possessive quantifiers.
  Strip `*+` when porting; use two-stage matchers for lookahead.
- **MCP SDK v1.6** — `Server.AddTool(t, h)` (two args, not aggregate).
  `StdioTransport{}` zero-value, no factory. JSON marshal of string
  returns needs special casing in `mcp/tool.go`.
- **`detectors_register.go` is a choke point** — see above.
- **gosec @v2.21.4 fails to build under Go 1.25** — pinned to v2.22.0.
- **GO-2026-4918 (HTTP/2 SETTINGS DoS)** reachable from
  `review.Client.Review` — fixed in Go 1.25.10 (our toolchain pin).

## Where to look next

- Architecture & components → [`docs/project/architecture.md`](docs/project/architecture.md)
- Conventions (full) → [`docs/project/conventions.md`](docs/project/conventions.md)
- Build & release → [`shared/runbooks/release-go.md`](shared/runbooks/release-go.md)
- MCP integration → [`README.md#mcp-integration`](README.md#mcp-integration)
- Internal SSoT → [`CLAUDE.md`](CLAUDE.md)
