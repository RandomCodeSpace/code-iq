# 00 — Project overview

## What it is

**codeiq** is a deterministic code-knowledge-graph builder. It scans a codebase, extracts 100 detector-defined patterns (REST endpoints, message queues, DB queries, auth filters, frontend components, IaC resources, …), and writes them as a graph of typed nodes + edges into an embedded Kuzu database. A read-only stdio MCP server then exposes the graph to LLM agents (Claude Code, Cursor) so they can answer "where is this called", "what depends on this", "what's the blast radius if I change X" without ever executing the code.

**The pipeline contains zero LLM calls.** Same input → same output, byte-for-byte. The only LLM touch-point is the opt-in `codeiq review` subcommand which uses the indexed graph as evidence for an Ollama (local or cloud) chat completion to produce PR review comments.

Source-of-truth entry point: [`cmd/codeiq/main.go`](../cmd/codeiq/main.go) → [`internal/cli/root.go`](../internal/cli/root.go).

## Target users

- **Developers** working on polyglot or unfamiliar codebases who need a fast structural map (`codeiq find endpoints`, `codeiq topology`).
- **AI coding agents** (Claude Code, Cursor) that need ground-truth structural facts about a codebase before suggesting changes. The MCP server is the primary integration path.
- **Reviewers** who want LLM-assisted PR review grounded in actual call/depends-on relationships rather than the diff alone (`codeiq review`).

## Core features

| Feature | Where |
|---|---|
| Static-analysis pipeline (FileDiscovery → tree-sitter / regex → 100 detectors → GraphBuilder → SQLite cache → Kuzu) | [`internal/analyzer/`](../internal/analyzer/) |
| 100 detectors across 35+ languages | [`internal/detector/`](../internal/detector/) (see [03-code-map](03-code-map.md)) |
| Kuzu embedded graph with native FTS (BM25-ranked search) | [`internal/graph/`](../internal/graph/) |
| 10 MCP tools (6 consolidated mode-driven + 4 specialized) | [`internal/mcp/`](../internal/mcp/) |
| Deterministic graph (confidence-aware node merge, canonical edge dedup, phantom-edge drop) | [`internal/analyzer/graph_builder.go`](../internal/analyzer/graph_builder.go) |
| LLM-driven PR review (Ollama local + cloud) | [`internal/review/`](../internal/review/) |
| Single static Go binary, ~25 MB | [`cmd/codeiq/main.go`](../cmd/codeiq/main.go) + Goreleaser |

## Current status — v0.4.1 / v0.4.2 (in flight)

Production-ready surface:
- **CLI subcommands** (index / enrich / mcp / stats / query / find / cypher / flow / graph / topology / review / cache / plugins / version) — all wired, all backed by tests.
- **MCP server** — 10 user-facing tools, read-only, mutation gate on `run_cypher`. Used in real Claude Code / Cursor configs.
- **CGO build** — linux/amd64, linux/arm64, darwin/arm64 release artifacts via Goreleaser with SBOMs + Cosign keyless signatures.
- **883+ tests** passing (CI: `go-ci` workflow runs vet + test -race + staticcheck + gosec + govulncheck).
- **OOM-fix verified** at `~/projects/`-scale (49k files / 187k nodes / 414k edges, peak RSS ~2 GiB).
- **Native Kuzu FTS** (v0.11.3) with BM25 ranking for label + lexical searches.

Experimental / partial:
- `codeiq review` — works end-to-end against local Ollama; Ollama Cloud path tested but the default endpoint is local. Output format is markdown or JSON.

Not implemented (despite mentions in older docs):
- **`codeiq config <action>`** — CLAUDE.md historically listed this; no `internal/cli/config.go` exists. The root `--config` flag still loads `codeiq.yml`.
- **REST API / web UI** — deleted in Phase 6 cutover (PR #132). Never coming back.

## Production-ready vs experimental

| Surface | State | Notes |
|---|---|---|
| CLI core (index / enrich / stats / find / query / cypher) | Production | 880+ tests, perf-gate CI |
| MCP stdio server (10 tools) | Production | Read-only; mutation gate tested |
| Kuzu 0.11.3 + native FTS | Production | Migrated from 0.7.1 with CONTAINS fallback retained |
| Goreleaser release pipeline | Production | Cosign keyless via GitHub OIDC + Sigstore Rekor |
| `codeiq review` (LLM PR review) | Beta | Works; quality depends on the LLM endpoint |
| Detector coverage | Mixed | 100 detectors; some are lexical-only (regex), AST refinement is a per-detector concern |

## Release history (after the v0.4.0 reset)

All earlier tags (`v0.0.x` … `v0.3.0`, `v1.0.0`) were deleted from GitHub because the Go module proxy (proxy.golang.org) permanently caches every published version's content — reusing a deleted tag name serves the old (often Python-prototype) zip. v0.4.0 is the first never-used version after the cleanup.

| Tag | Date | Notes |
|---|---|---|
| v0.4.0 | 2026-05-14 | Fresh start. Includes OOM fix + Kuzu 0.11.3 + native FTS + module hoist + 5 enrich correctness fixes. |
| v0.4.1 | 2026-05-14 | CI/dependency hygiene patch (release-darwin race fix + Dependabot bumps). |

See [`docs/adr/0001-current-architecture.md`](adr/0001-current-architecture.md) for the architectural decisions behind today's shape.
