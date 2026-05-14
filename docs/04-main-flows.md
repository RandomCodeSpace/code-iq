# 04 — Main flows

Every flow lists **entry point**, **key files**, and **failure modes**. Authentication / login is **not present** — codeiq has no user-facing service; the only auth-adjacent code is detector logic that *finds* auth patterns in scanned codebases.

## 1. Index flow — `codeiq index <path>`

**Entry point:** [`internal/cli/index.go`](../internal/cli/index.go) → [`analyzer.Run()`](../internal/analyzer/analyzer.go).

**Steps:**

1. File discovery via `git ls-files` (fallback: `filepath.Walk` with `DefaultExcludeDirs` excluding `node_modules`, `vendor`, `target`, `.git`, `dist`, `build`, `.gradle`, `.idea`, `__pycache__`, `.tox`, `.eggs`, `venv`, `.venv`).
2. Extension → `parser.Language` mapping in [`parser.LanguageFromExtension`](../internal/parser/parser.go).
3. **Worker pool** (default `2 × GOMAXPROCS`, override `--workers`). Per-file:
   - Read content.
   - Parse: tree-sitter for {Java, Python, TypeScript, Go}; structured parser for YAML/JSON/TOML/INI/properties; regex-only fallback otherwise.
   - Iterate every `Detector` whose `SupportedLanguages()` covers the file's language. Pass `detector.Context{FilePath, Language, Content, Tree, …}`.
4. **GraphBuilder** ([`graph_builder.go`](../internal/analyzer/graph_builder.go)):
   - `mergeNode` performs confidence-aware union: donor only fills keys the survivor doesn't have, so a `Spring` detector's `framework=spring` stamp survives a generic `auth` detector's overwrite attempt.
   - Edges are deduped on canonical `(source_id, target_id, kind)`.
5. **Snapshot** sorts nodes + edges by ID for determinism and drops "phantom edges" — edges whose endpoint isn't in the node set. Visible via `analyzer.Stats.DroppedEdges`.
6. **Cache write** in batches (`--batch-size`, default 500). Nodes + edges go to `nodes` / `edges` tables keyed by file content hash. JSON-serialized in the `data` column.

**Entry point keys / important files:**

- [`internal/cli/index.go`](../internal/cli/index.go) — flag parsing + analyzer wiring
- [`internal/analyzer/analyzer.go`](../internal/analyzer/analyzer.go) — pipeline loop
- [`internal/analyzer/file_discovery.go`](../internal/analyzer/file_discovery.go) — `git ls-files` + filesystem fallback
- [`internal/parser/parser.go`](../internal/parser/parser.go) — language detection
- [`internal/detector/detector.go`](../internal/detector/detector.go) — `Detector` interface + `Default` registry
- [`internal/analyzer/graph_builder.go`](../internal/analyzer/graph_builder.go) — dedup + snapshot
- [`internal/cache/cache.go`](../internal/cache/cache.go) — batched writes

**Failure modes:**

- **Empty registry** — detector category not blank-imported in [`detectors_register.go`](../internal/cli/detectors_register.go) → 0 emissions for that language. Symptom: `codeiq plugins list` doesn't show the detector. Already-bitten by this; the auto-import check is one of the most important correctness invariants.
- **Tree-sitter parse error** — detector falls back to regex-only path; some emissions degrade in fidelity (e.g. `framework` may be missing). Logged at `-v`.
- **Large file** — Tree-sitter has memory cost ~O(file_size); the worker pool concurrency × tree size can OOM if `--workers` is too high. Default `2 × GOMAXPROCS` is safe up to ~50k files / 15 GiB hosts.
- **Cache write contention** — SQLite WAL handles concurrent reads + one writer. Writes are batched on a single channel; backpressure shows up as slow `index`.

## 2. Enrich flow — `codeiq enrich <path>`

**Entry point:** [`internal/cli/enrich.go`](../internal/cli/enrich.go) → [`analyzer.RunEnrich(EnrichOptions)`](../internal/analyzer/enrich.go).

**Steps:**

1. Open SQLite cache read-only.
2. Stream every cached node + edge into a `GraphBuilder` to re-snapshot (sort).
3. **Linkers** ([`internal/analyzer/linker/`](../internal/analyzer/linker/)) — TopicLinker, EntityLinker, ModuleContainmentLinker — emit cross-file edges by name matching (e.g. a `produces topic="users.created"` and a `consumes topic="users.created"` get linked even though they live in different files).
4. **LayerClassifier** stamps `layer = frontend | backend | infra | shared | unknown` on every node using filename heuristics + framework hints.
5. **Intelligence layer** ([`internal/intelligence/extractor/`](../internal/intelligence/extractor/)):
   - `ExtractFromTree` runs once per file (tree-sitter parsed once, not per-node — Phase A OOM fix).
   - Surfaces `prop_lex_comment` (doc comments / JSDoc / docstring text) and `prop_lex_config_keys` (extracted key lists from YAML/JSON config files).
   - Per-file goroutine pool is `2 × GOMAXPROCS`-bounded (Phase A OOM fix).
6. **ServiceDetector** ([`service_detector.go`](../internal/analyzer/service_detector.go)) walks the FS for build files (`pom.xml`, `package.json`, `go.mod`, `Cargo.toml`, `pyproject.toml`, `setup.py`, `Gemfile`, `composer.json`, `Package.swift`, `mix.exs`, `pubspec.yaml`, `stack.yaml`, `build.zig`, `dune-project`, `DESCRIPTION`, `BUILD`, `BUILD.bazel`, plus `.csproj`/`.fsproj`/`.vbproj`/`.gemspec`/`.cabal`/`.nimble` suffixes). One `SERVICE` node per module + `CONTAINS` edges to its child nodes. **IDs are path-qualified** (`service:<dir>:<name>`).
7. **Kuzu BulkLoad** ([`internal/graph/bulk.go`](../internal/graph/bulk.go)):
   - Open Kuzu writable with `BufferPoolBytes` capped at 2 GiB (override `--max-buffer-pool=N`).
   - Apply schema (idempotent — single `CodeNode` table + 28 REL tables).
   - Write CSV staging files with `csv.Writer{Comma: '|'}`.
   - `COPY <table> FROM '<csv>' (header=false, DELIM='|', QUOTE='"', ESCAPE='"')` — explicit QUOTE+ESCAPE so Kuzu honors Go's RFC-4180 quoting.
   - Batches of 50,000 rows (override `CODEIQ_BULK_BATCH_SIZE` env).
8. **FTS** ([`internal/graph/indexes.go`](../internal/graph/indexes.go)):
   - `INSTALL fts; LOAD EXTENSION fts;`
   - `CALL DROP_FTS_INDEX('CodeNode', '<name>');` (idempotent)
   - `CALL CREATE_FTS_INDEX('CodeNode', 'code_node_label_fts', ['label', 'fqn_lower']);`
   - `CALL CREATE_FTS_INDEX('CodeNode', 'code_node_lexical_fts', ['prop_lex_comment', 'prop_lex_config_keys']);`

**Tunable knobs (CLI flags on `enrich`):**

- `--memprofile=<path>` — writes a Go heap profile (`pprof.WriteHeapProfile`). Analyze with `go tool pprof -top -inuse_space <path>`.
- `--max-buffer-pool=N` — Kuzu BufferPoolSize override (bytes). Default 2 GiB.
- `--copy-threads=N` — Kuzu `MaxNumThreads`. Default `min(4, GOMAXPROCS)`.

**Failure modes:**

- **Duplicate primary key on COPY** — historically bit on `service:<name>` collisions across modules. Fixed by path-qualified IDs (#151). Symptom: `Copy exception: Found duplicated primary key value service:checkout`.
- **CSV "expected N values per row, but got more"** — JSON property values containing commas (#150 added pipe delim) or pipes (#153 added explicit `QUOTE`/`ESCAPE`). All known instances fixed.
- **TOML quoted-key emission** — `"check_sha" = ...` made it through with literal quotes in node IDs, breaking edge PK lookup. Fixed in `parseTOML` via `unquote()` on the key (#152).
- **OOM** — Phase A+B+C fix landed: parse-once-per-file, bounded extractor pool, 2 GiB BufferPool cap, `Snapshot()` nils dedup maps. Verified at ~/projects/-scale (49k files): peak RSS 1.8–2.2 GiB.
- **FTS extension missing** — Kuzu 0.11.3+ bundles it; `INSTALL fts` is a no-op when bundled. Pre-0.11.3 graphs fall through to CONTAINS predicates via the fallback path.

## 3. MCP server flow — `codeiq mcp <path>`

**Entry point:** [`internal/cli/mcp.go`](../internal/cli/mcp.go) → [`mcp.Server.Serve()`](../internal/mcp/server.go).

**Steps:**

1. Open Kuzu **read-only** (`graph.OpenReadOnly(path, query_timeout)`). Mutation gate is active for every `s.Cypher(...)` call.
2. Build `mcp.Deps` (store + intelligence + flow + review + max-results + max-depth caps).
3. Register tools via 3 helper functions:
   - `RegisterGraphUserFacing(srv, d)` → `run_cypher` + `read_file`.
   - `RegisterFlow(srv, d)` → `generate_flow`.
   - `RegisterConsolidated(srv, d)` → 6 mode-driven tools + `review_changes`.
4. Bind transport: `mcpsdk.StdioTransport{}` (zero value binds `os.Stdin`/`os.Stdout`).
5. `Server.Serve(ctx, transport)` — blocks until stdin closes or context cancels.

**Tool list (10 user-facing):**

| Tool | Modes / params |
|---|---|
| `graph_summary` | `overview` / `categories` / `capabilities` / `provenance` |
| `find_in_graph` | `nodes` / `edges` / `text` / `fuzzy` / `by_file` / `by_endpoint` |
| `inspect_node` | `neighbors` / `ego` / `evidence` / `source` |
| `trace_relationships` | `callers` / `consumers` / `producers` / `dependencies` / `dependents` / `shortest_path` |
| `analyze_impact` | `blast_radius` / `trace` / `cycles` / `circular_deps` / `dead_code` / `dead_services` / `bottlenecks` |
| `topology_view` | `summary` / `service` / `service_deps` / `service_dependents` / `flow` |
| `run_cypher` | Escape hatch — read-only Cypher. `CALL QUERY_FTS_INDEX` allow-listed. |
| `read_file` | Read source file content. Path-sandboxed to the indexed root. Full file or line range. |
| `generate_flow` | Architecture-flow diagrams. Views: `overview` / `ci` / `deploy` / `runtime` / `auth`. Formats: `json` / `mermaid` / `dot` / `yaml`. |
| `review_changes` | LLM-driven git-diff review via Ollama. Reads graph + shells out to `git`; never writes to `.codeiq/`. |

**Key files:**

- [`internal/mcp/server.go`](../internal/mcp/server.go) — `Server`, `Registry`, `Serve()`
- [`internal/mcp/tool.go`](../internal/mcp/tool.go) — `Tool` struct + `asSDKTool` conversion (special-cases string returns for `generate_flow`)
- [`internal/mcp/tools_consolidated.go`](../internal/mcp/tools_consolidated.go) — 6 mode-driven tools
- [`internal/mcp/tools_graph.go`](../internal/mcp/tools_graph.go) — narrow tool builders (Go-API delegation targets) + `run_cypher` + `read_file`
- [`internal/graph/mutation.go`](../internal/graph/mutation.go) — `MutationKeyword` regex gate

**Failure modes:**

- **`run_cypher` blocked** — query contains CREATE/DELETE/SET/REMOVE/MERGE/DROP/FOREACH/LOAD CSV/COPY/DETACH or a non-allow-listed CALL. Surfaced as a regular tool-call error with the blocked keyword named.
- **Cypher binder error** — Kuzu's parser surfaces "Variable n is not in scope" or "Parameter X not found in EXISTS subquery" for known binder limitations. The query layer codes around these (e.g. `properties(nodes(p), 'id')` instead of list comprehension).
- **Path traversal in `read_file`** — sandboxed to the indexed root. Attempted `../` resolves outside the root → error envelope.
- **MCP arg-name mismatches** — historically the 6 consolidated tools delegated with wrong arg names (PR #149 fix). Parity tests in [`internal/mcp/tools_consolidated_parity_test.go`](../internal/mcp/tools_consolidated_parity_test.go) lock the names down.

## 4. PR-review flow — `codeiq review`

**Entry point:** [`internal/cli/review.go`](../internal/cli/review.go) → [`review.NewService(...).Review(ctx, ...)`](../internal/review/).

**Steps:**

1. Shell out to `git diff <base>..<head>` for the diff.
2. Parse the diff into hunks ([`internal/review/diff.go`](../internal/review/diff.go) — Inference based on filename).
3. For each touched file path, query Kuzu for evidence:
   - Nodes defined in the file
   - Inbound semantic edges to those nodes (callers, depends-on)
4. Build the LLM prompt: diff + evidence + review-style guidance.
5. POST to Ollama `/v1/chat/completions` (OpenAI-compatible). Default base URL `http://localhost:11434`. If `OLLAMA_API_KEY` is set, switch to Ollama Cloud.
6. Parse the response into structured review JSON, or render as Markdown if `--format markdown`.

**Key files:**

- [`internal/review/client.go`](../internal/review/client.go) — Inference: HTTP client wrapping `/v1/chat/completions`
- [`internal/review/service.go`](../internal/review/service.go) — Inference: orchestration glue
- [`internal/review/graphctx.go`](../internal/review/graphctx.go) — Kuzu queries for change-context evidence

**Failure modes:**

- **No Ollama running** — connection refused on localhost:11434. Falls back to a clear error rather than hanging.
- **Model unavailable** — `ollama run` returns 404 for unknown model; surfaced as a clean error.
- **HTTP/2 SETTINGS infinite-loop CVE** — the Go 1.25.10 toolchain pin includes the fix for GO-2026-4918, reachable via `review.Client.Review` (per [`.github/workflows/go-ci.yml`](../.github/workflows/go-ci.yml) comment).
- **Stale graph evidence** — if the diff touches files that haven't been re-indexed, evidence is partial. The review still runs; quality is operator's responsibility.

## 5. Error handling

There is no centralized error-handling module. Conventions:

| Layer | Pattern |
|---|---|
| CLI subcommands | Return `error` from `RunE`. Cobra prints + sets exit code (1 for usage error, 2 for runtime). |
| Detector | `Detect(ctx) *Result` — nil-tolerant. Detectors return `EmptyResult()` on no match; never panic on malformed input. |
| Graph layer | Every `s.Cypher(...)` returns `(rows, error)`. Mutation-gate rejections surface as `graph: write query rejected on read-only store (blocked keyword: X)`. |
| MCP tool handler | Catches errors, wraps in `NewErrorEnvelope(code, err, RequestID(ctx))` so the MCP protocol surface stays well-formed. |
| Logging | `fmt.Fprintln(os.Stderr, ...)` with verbosity controlled by root `-v` flag. No structured-logging library. Inference: shipping concise to stay supply-chain-clean. |

## 6. Background jobs / data ingestion

codeiq does not run background jobs. Every action is operator-driven (`codeiq <cmd>`). The CI perf-gate is the closest thing to a scheduled job — it runs `index` + `enrich` against `testdata/fixture-multi-lang` on every PR.
