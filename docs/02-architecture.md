# 02 — Architecture

## One-page summary

codeiq is a CLI binary plus an MCP stdio server. They share the same Go module and Cobra command tree; the MCP server is just `codeiq mcp` and reads from the same Kuzu graph the rest of the CLI writes to.

```
                ┌──────────────────────────────────────────────────────────┐
                │              codeiq (single static binary)               │
                │                                                          │
   source       │   ┌───────────┐    ┌──────────┐    ┌─────────────┐       │
   tree   ─────►│   │ index     │───►│ SQLite   │───►│ enrich      │       │
                │   │ (analyzer)│    │ cache    │    │ (analyzer)  │       │
                │   └───────────┘    └──────────┘    └──────┬──────┘       │
                │                                           │              │
                │   ┌────────────────────────────────────┐  ▼              │
                │   │ Read-only consumers:               │ ┌─────────────┐ │
                │   │   stats, find, query, cypher,      │ │   Kuzu      │ │
                │   │   flow, graph, topology, review    │◄┤   graph     │ │
                │   │   mcp (stdio JSON-RPC, 10 tools)   │ │             │ │
                │   └────────────────────────────────────┘ └─────────────┘ │
                └──────────────────────────────────────────────────────────┘
```

## Components

| Component | Package | What it does |
|---|---|---|
| **CLI** | [`internal/cli/`](../internal/cli/) | Cobra command tree. One file per subcommand. Root is [`root.go`](../internal/cli/root.go). Every detector package is blank-imported in [`detectors_register.go`](../internal/cli/detectors_register.go) — the **registration choke point** (forget it and the binary ships with an empty detector registry). |
| **Analyzer (index)** | [`internal/analyzer/`](../internal/analyzer/) | Orchestrates: file discovery → parse → detector pool → GraphBuilder → SQLite writes. Entry: `analyzer.Run()` in [`analyzer.go`](../internal/analyzer/analyzer.go). |
| **Analyzer (enrich)** | [`internal/analyzer/enrich.go`](../internal/analyzer/enrich.go) | Loads cache → applies linkers (topic, entity, module-containment) → LayerClassifier → LexicalEnricher → LanguageEnricher → ServiceDetector → Kuzu bulk-load via COPY FROM. |
| **Detectors** | [`internal/detector/`](../internal/detector/) | 100 implementations of the [`detector.Detector` interface](../internal/detector/detector.go). Each registers itself in `init()` with `detector.RegisterDefault(...)`. |
| **Parser** | [`internal/parser/`](../internal/parser/) | Tree-sitter wrappers for Java/Python/TypeScript/Go, plus a hand-rolled structured parser for YAML/JSON/TOML/INI/properties. Falls back to regex-only on parse failure. |
| **GraphBuilder** | [`internal/analyzer/graph_builder.go`](../internal/analyzer/graph_builder.go) | Confidence-aware dedup (`mergeNode`), canonical `(source, target, kind)` edge dedup, deterministic `Snapshot()` with phantom-edge drop. |
| **Graph (Kuzu facade)** | [`internal/graph/`](../internal/graph/) | Wraps `github.com/kuzudb/go-kuzu` v0.11.3. Read-only mode (`OpenReadOnly`) used by MCP + stats. Mutation gate ([`mutation.go`](../internal/graph/mutation.go)) rejects write-side Cypher on read-only opens; allow-lists `CALL QUERY_FTS_INDEX`. |
| **SQLite cache** | [`internal/cache/`](../internal/cache/) | Five tables (cache_meta, files, nodes, edges, analysis_runs). WAL mode. `CacheVersion = 6`. |
| **Intelligence layer** | [`internal/intelligence/`](../internal/intelligence/) | Lexical enricher + per-language extractors (java, python, typescript, golang) that surface high-signal lexical features (doc comments, config keys) for the lexical-FTS index. |
| **MCP server** | [`internal/mcp/`](../internal/mcp/) | Stdio JSON-RPC 2.0 over `modelcontextprotocol/go-sdk` v1.6. 10 user-facing tools (see [03-code-map](03-code-map.md) for the full list). |
| **Query layer** | [`internal/query/`](../internal/query/) | Cypher templates for service / topology / stats / dead-code / cycle-detection. Used by the CLI subcommands and the MCP delegation layer. |
| **Flow** | [`internal/flow/`](../internal/flow/) | Architecture-flow diagram engine (mermaid / dot / yaml output). Reads Kuzu; doesn't write. |
| **Review** | [`internal/review/`](../internal/review/) | Diff parser + Ollama-compatible chat client + ReviewService. Pulls evidence from the Kuzu graph (callers, depends-on) and asks the LLM for review comments. |

## Data flow

### `codeiq index <path>`

1. **File discovery** ([`internal/analyzer/file_discovery.go`](../internal/analyzer/file_discovery.go)) — `git ls-files` first, dir-walk fallback. Maps extension → `parser.Language` via [`parser.LanguageFromExtension`](../internal/parser/parser.go).
2. **Worker pool** (default `2 × GOMAXPROCS`, override via `--workers`). Each worker:
   - Reads file content
   - Parses (tree-sitter for {Java, Python, TS, Go}, structured for YAML/JSON/TOML/INI/properties, regex-only fallback)
   - Runs every `Detector` whose `SupportedLanguages()` covers the file's language
3. **GraphBuilder** aggregates emissions, dedup-merging nodes (confidence-aware property union — see `mergeNode`) and edges (canonical `(source, target, kind)`).
4. **Cache writes** in batches of `--batch-size` (default 500) — JSON-serialized nodes/edges keyed by content hash so subsequent runs can incrementally skip unchanged files.

Returns `analyzer.Stats{Files, Nodes, Edges, DedupedNodes, DedupedEdges, DroppedEdges}` — the dedup/drop counters are visible to the operator so graph hygiene is diagnosable.

### `codeiq enrich <path>`

1. Read every row from SQLite cache.
2. Re-snapshot (sort) for determinism.
3. **Linkers** ([`internal/analyzer/linker/`](../internal/analyzer/linker/)) — TopicLinker, EntityLinker, ModuleContainmentLinker — emit cross-file edges (e.g. `consumes` between a Kafka-producer detector and a Kafka-consumer detector that both reference the same topic name).
4. **LayerClassifier** stamps every node with one of `frontend | backend | infra | shared | unknown`.
5. **LexicalEnricher + LanguageEnricher** populate `prop_lex_comment` and `prop_lex_config_keys` for the lexical FTS index.
6. **ServiceDetector** ([`internal/analyzer/service_detector.go`](../internal/analyzer/service_detector.go)) walks the filesystem for build files (pom.xml, package.json, go.mod, Cargo.toml, …) and emits one `SERVICE` node per detected module, plus `CONTAINS` edges to its child nodes. **IDs are path-qualified** (`service:<dir>:<name>`) so two modules sharing a name don't collide on Kuzu primary key.
7. **Kuzu BulkLoad** ([`internal/graph/bulk.go`](../internal/graph/bulk.go)) — CSV staging with `DELIM='|', QUOTE='"', ESCAPE='"'` (RFC-4180), batches of 50k rows.
8. **`CreateIndexes()`** ([`internal/graph/indexes.go`](../internal/graph/indexes.go)) — `INSTALL fts; LOAD EXTENSION fts;` then `CALL CREATE_FTS_INDEX` over `(label, fqn_lower)` and `(prop_lex_comment, prop_lex_config_keys)`.

### `codeiq mcp <path>`

1. Open Kuzu read-only (`OpenReadOnly`) — mutation gate enforces.
2. Register 10 tools via the registry in [`internal/mcp/server.go`](../internal/mcp/server.go).
3. Bind to `os.Stdin`/`os.Stdout` via `mcpsdk.StdioTransport{}`.
4. Serve. Each tool call → Cypher → JSON response. Every stat/find/query CLI subcommand has an MCP analog.

See [04-main-flows.md](04-main-flows.md) for per-flow entry points and failure modes.

## Storage choices

| Surface | Engine | Why |
|---|---|---|
| Analysis cache | **SQLite** (`mattn/go-sqlite3` 1.14.44, WAL mode) | Cheap incremental dedup. Content-hash keyed so an unchanged file skips re-parse. |
| Graph store | **Kuzu** v0.11.3 (`kuzudb/go-kuzu`) | Embedded — no separate daemon. Property-graph model + native Cypher. Bundled FTS (v0.11.3+). |
| FTS index | **Kuzu native FTS** (BM25) | Replaced CONTAINS predicates from the v0.7.1 era. Auto-suffix `*` on single-token queries preserves prefix-match UX. CONTAINS fallback retained for pre-enrich graphs. |

Both stores live under `<repo>/.codeiq/`. They're gitignored.

## External systems

- **Ollama** (HTTP, default `http://localhost:11434`) — only used by `codeiq review`. The OpenAI-compat `/v1/chat/completions` endpoint.
- **Ollama Cloud** — alternate base URL when `OLLAMA_API_KEY` is set.
- **GitHub OIDC + Sigstore Fulcio + Rekor** — release-time only; signs `checksums.sha256` keyless. No runtime touch.

That's the entire external-system list. **No telemetry, no analytics, no auto-update.**

## Important tradeoffs

| Choice | Tradeoff |
|---|---|
| **CGO mandatory** | Cross-compile is harder; CGO_ENABLED=0 builds don't work. Buys embedded Kuzu + SQLite + tree-sitter — no separate daemons. |
| **Detector registration choke point** ([`detectors_register.go`](../internal/cli/detectors_register.go)) | Forgetting the blank import silently ships an empty registry. Buys: Go linker drops unimported packages → small binary. |
| **Lower-cased columns in CodeNode (`label_lower`, `fqn_lower`)** | Schema-level duplication. Originally for case-insensitive CONTAINS; now redundant with FTS but kept for fallback. |
| **Single-table polymorphic CodeNode** | Every NodeKind shares one Kuzu table with `kind` as a column. Simpler queries, but loses type-discriminated index optimizations Kuzu could do with per-label tables. |
| **Inline LIMIT for recursive `[*1..N]` patterns** | Kuzu still requires the upper bound to be a literal. Detected, contained, documented in [10-known-risks-and-todos.md](10-known-risks-and-todos.md). |
| **Mutation gate via regex keyword filter** | Pure string-level matching (`CREATE`, `MERGE`, `DELETE`, etc.). Not a full Cypher parser — adversarial inputs might bypass via formatting tricks. Belt-and-braces alongside Kuzu's own `OpenReadOnly` system flag. |
| **No telemetry / no auto-update** | Operator has to track new releases. Buys: zero data collection, zero runtime network. |
| **Goreleaser `draft: true`** | Every release needs manual `gh release edit --draft=false`. Buys: maintainer review before broadcast. |

See [`docs/adr/0001-current-architecture.md`](adr/0001-current-architecture.md) for the decision rationale.
