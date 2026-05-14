# ADR 0001 — Current architecture

> **Status:** Accepted. **Date:** 2026-05-14. **Deciders:** project maintainer + AI pairing.
>
> This ADR is retrospective — it documents the architecture as it exists at v0.4.1 and the reasoning behind the choices that produced it. Subsequent ADRs (0002+) should document *changes* relative to this baseline.

## Context

codeiq started as a Java/Spring Boot reference implementation with a Neo4j embedded graph, a React SPA, and a REST API. Phase 6 cutover (PR #132 in the now-deleted history) replaced the entire Java side with a single static Go binary backed by Kuzu, deleted the React SPA, and dropped the REST API in favor of a stdio MCP server. The Go module then lived at `/go/` inside the repo for one release cycle (v0.3.0), and was hoisted to the repo root in v0.4.0 (PR #162).

This ADR captures the major decisions that produced today's shape:

1. CLI binary + stdio MCP server (no REST, no UI, no daemon)
2. Kuzu embedded graph (CGO) for storage
3. SQLite cache as an intermediate, content-hash-keyed store
4. Detector pattern with a single `Detector` interface + 100 implementations
5. Read-only MCP surface with a mutation gate
6. Goreleaser + Cosign keyless via GitHub OIDC for releases
7. No telemetry, no auto-update, no outbound network during core flows
8. Go module at repo root, post-hoist

## Decision

### 1. CLI + stdio MCP, no daemon

**Choice:** Ship a single static Go binary. Surface to humans via `codeiq <subcommand>`; surface to LLM agents via the stdio MCP protocol (`codeiq mcp`). No long-running server, no port to bind, no auth layer.

**Why:**
- AI agents (Claude Code, Cursor) integrate via stdio MCP — that's the contract.
- Humans get the same Cypher / graph queries as the AI through the CLI.
- No daemon means no auth surface to harden, no port to expose, no service to monitor.
- A second process for a developer-host tool would be friction operators don't want.

**Tradeoff:** State management is per-invocation. A long-running `mcp` server holds an open Kuzu read-only handle; CLI subcommands open + close per invocation. Concurrent invocations on the same `.codeiq/graph/` are not coordinated — the operator gets to enforce sequencing.

### 2. Kuzu embedded graph, CGO

**Choice:** Use Kuzu 0.11.3 via `github.com/kuzudb/go-kuzu`. CGO links the Kuzu C++ engine into the binary; data lives on disk at `.codeiq/graph/codeiq.kuzu/`.

**Why:**
- Property-graph queries (Cypher) are the natural fit for the questions agents ask ("what calls X", "what depends on Y", "blast radius of changing Z").
- Embedded means no daemon, no separate install. CGO + a static binary is the price.
- Kuzu 0.11.3 bundles the FTS extension — no network install needed, no air-gap concerns.
- Native BM25 ranking via `CALL QUERY_FTS_INDEX` replaces the substring-CONTAINS hacks from Kuzu 0.7.1.

**Alternatives considered:**
- **Neo4j embedded** — was the Java-era choice. Heavier (JVM), and the Java-side process is gone.
- **DuckDB** — relational, not graph; recursive CTEs work but the Cypher ergonomics for blast-radius queries are better.
- **SQLite with adjacency tables** — would work; loses the FTS index ergonomics and recursive-pattern performance.

**Tradeoff:** Kuzu's on-disk format may change across minor versions. The graph is rebuildable from the SQLite cache (which is stable), so we treat Kuzu as rebuildable and the cache as durable.

### 3. SQLite cache as content-hash intermediary

**Choice:** Run a two-phase pipeline: `index` writes to SQLite cache (content-hash keyed), `enrich` loads cache → Kuzu graph. Both stores live under `.codeiq/`.

**Why:**
- The cache lets a re-run skip files whose content hash hasn't changed.
- It separates "detection" (lossy regex/AST work) from "graph assembly" (deterministic dedup/snapshot) — each phase has clean invariants.
- SQLite is the most reliable embedded store in the ecosystem and stable across versions.
- The cache also serves the `codeiq cache inspect` debugging surface.

**Tradeoff:** Two storage layers + a transient. Operators sometimes wonder which to nuke. Answer: `rm -rf .codeiq/` rebuilds both. The cache is in WAL mode so we don't lose performance to the indirection.

### 4. Detector pattern: one interface, 100 impls

**Choice:** Every emitter implements:
```go
type Detector interface {
    Name() string
    SupportedLanguages() []string
    DefaultConfidence() model.Confidence
    Detect(ctx *Context) *Result
}
```

Each detector registers itself with `detector.RegisterDefault(NewMyDetector())` in `init()`. The package leaf must be blank-imported in `internal/cli/detectors_register.go`.

**Why:**
- Adding a new framework / detector is a single file. The interface is tight.
- Detectors are stateless → safe to call concurrently from the worker pool.
- Confidence is monotonic; the `DefaultConfidence()` stamp is enforced at the registration boundary.
- The blank-import gate keeps the Go linker from dropping packages we want present.

**Tradeoff:** The blank-import is a footgun. Forgetting it ships an empty registry silently. We test it indirectly via `codeiq plugins list` + the determinism tests per detector. A static `go vet`-style check that compares the leaf packages under `internal/detector/` to the blank-import list would be a worthwhile addition.

### 5. Read-only MCP + mutation gate

**Choice:** The MCP server (`codeiq mcp`) opens Kuzu with `OpenReadOnly`. The `run_cypher` tool runs every query through a regex-based [`MutationKeyword`](../../internal/graph/mutation.go) check that rejects CREATE/DELETE/SET/REMOVE/MERGE/DROP/FOREACH/LOAD CSV/COPY/DETACH and any CALL outside an allow-list (`db.*`, `show_*`, `table_*`, `current_setting`, `table_info`, `query_fts_index`).

**Why:**
- AI agents should not mutate the graph. Mutations come from `codeiq index/enrich` only.
- Belt-and-braces: the Kuzu `OpenReadOnly` flag is the engine-level enforcement; the keyword gate is a fast-fail at the binding layer.
- The allow-list for CALL is narrow enough that a typo lets `db.show_indexes` through (read) but a typo on `db.delete` doesn't (no matching prefix).

**Tradeoff:** Regex keyword matching is not a Cypher parser. Adversarial inputs (comments smuggling keywords, unicode whitespace) might in principle bypass the gate. The recommended mitigation is fuzzing the gate (see [`docs/10-known-risks-and-todos.md`](../10-known-risks-and-todos.md)).

### 6. Goreleaser + Cosign keyless via GitHub OIDC

**Choice:** Releases ship as `vX.Y.Z` tag pushes. The CI workflow (`release-go.yml` for linux/amd64+arm64; `release-darwin.yml` for darwin/arm64) builds, generates SBOMs (Syft), signs the checksums file via cosign keyless (Fulcio ephemeral cert from GitHub OIDC; Sigstore Rekor transparency log), and creates a draft GitHub Release. The maintainer publishes manually.

**Why:**
- No long-lived signing key reduces blast radius.
- SBOM + cosign signatures meet SLSA-level expectations for a supply-chain-conscious tool.
- The `draft: true` default forces a human review before broadcast.

**Tradeoff:** Two workflows on one tag push race on the Release object. The early `release-darwin` poll budget was tight (90s) and timed out frequently. Fixed in PR #165 (15-min budget + early-bail on upstream failure).

### 7. No telemetry, no auto-update, no outbound network

**Choice:** The `index` / `enrich` / `mcp` / `stats` / `find` / `query` / `cypher` / `flow` / `graph` / `topology` / `cache` / `plugins` / `version` subcommands make zero outbound HTTP calls. The only network touch in the entire binary is the `codeiq review` subcommand against Ollama.

**Why:**
- Air-gap and security-sensitive environments need this. The repo positions itself as deployable behind a corporate firewall.
- Telemetry would create a privacy expectation we can't (and won't) maintain.
- Auto-update would add a verification surface we don't currently sign for runtime use.

**Tradeoff:** Operators have to track new releases manually. Mitigated by Dependabot keeping the project's own deps current and the `version` subcommand printing build info clearly.

### 8. Module at repo root (post-hoist)

**Choice:** As of v0.4.0 (PR #162), the Go module lives at the repository root. Module path: `github.com/randomcodespace/codeiq`. Before v0.4.0, it lived at `/go/`; that subdirectory is gone.

**Why:**
- `go install github.com/randomcodespace/codeiq/cmd/codeiq@vX.Y.Z` resolves directly to the matching tag, no subdir hop.
- Tags are clean (`vX.Y.Z`, not `go/vX.Y.Z`).
- Standard Go project layout, easier onboarding.

**Tradeoff:** `git blame` paths changed for every Go file. The historical commits still exist; `git log --follow <file>` works. One-time disruption for a permanent UX win.

## Consequences

**Positive:**
- Single static binary, ~25 MB, no daemons, no external services in core flows.
- Deterministic output. Same input ⇒ same Kuzu graph, byte-for-byte.
- Read-only MCP surface that's safe to wire into any agent.
- Supply-chain story: cosign keyless via GitHub OIDC, Sigstore Rekor, Syft SBOMs, OpenSSF Scorecard.
- Pre-1.0 but production-grade in the surfaces that matter (884+ tests, perf-gate CI, 6 security scanners in CI).

**Negative:**
- CGO is mandatory. Can't cross-compile darwin from linux without OSXCross or a macOS host.
- Detector registration is a choke-point footgun.
- Two storage layers (SQLite + Kuzu) means two paths to nuke when state corrupts.
- Mutation gate is regex-based — not a full Cypher parser.
- `proxy.golang.org` immutability means deleted tags poison their version numbers permanently.

**Neutral:**
- No telemetry → no usage data to improve UX from.
- No auto-update → users may run stale binaries.
- Operator-driven (no daemon, no scheduler) → simple but requires operator discipline.

## Open follow-ups

- Fuzz the mutation gate.
- Property-fuzz the CSV bulk-load writer.
- Snapshot tests for tree-sitter grammar outputs.
- Document `gh attestation verify` for release consumers.
- Decide on `parity/` — keep or delete.
- Implement `codeiq config <action>` or formally remove the historical reference.

## References

- [`docs/02-architecture.md`](../02-architecture.md) — current component map
- [`docs/04-main-flows.md`](../04-main-flows.md) — per-flow entry points
- [`docs/06-data-model.md`](../06-data-model.md) — Kuzu + SQLite schemas
- [`docs/10-known-risks-and-todos.md`](../10-known-risks-and-todos.md) — open follow-ups
- [`internal/detector/detector.go`](../../internal/detector/detector.go) — Detector interface
- [`internal/graph/mutation.go`](../../internal/graph/mutation.go) — mutation gate
- [`.goreleaser.yml`](../../.goreleaser.yml) — release pipeline config
