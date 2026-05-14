# 03 — Code map

> All paths are repo-root-relative. Module: `github.com/randomcodespace/codeiq`. CGO required everywhere. ~395 Go files in `internal/` + `cmd/`.

## Top level

```
codeiq/
├── cmd/                — main package(s)
├── internal/           — production code (393 .go files)
├── testdata/           — fixtures (fixture-minimal, fixture-multi-lang)
├── scripts/            — release / git-setup shell helpers
├── .github/workflows/  — 6 workflows: go-ci, perf-gate, release-go, release-darwin, security, scorecard
├── .goreleaser.yml     — Goreleaser v2 config (CGO multi-arch + cosign + syft)
├── .gitignore
├── go.mod              — module root post-hoist (PR #162)
├── go.sum
└── LICENSE             — MIT
```

## `cmd/`

| File | Purpose |
|---|---|
| [`cmd/codeiq/main.go`](../cmd/codeiq/main.go) | The only ship-able binary. `func main()` is a thin `os.Exit(cli.Execute())` shim — all logic lives in `internal/cli`. |
| [`cmd/extcheck/main.go`](../cmd/extcheck/main.go) | Build-time helper (not shipped). Inference: external-link / extension verification — verify with `head cmd/extcheck/main.go` if you touch it. |

## `internal/cli/` — Cobra command tree

13 subcommand files (excluding tests). Every subcommand registers itself via `init()` (see [`root.go`](../internal/cli/root.go)).

| File | Cobra `Use` | Subcommand class |
|---|---|---|
| [`root.go`](../internal/cli/root.go) | `codeiq` | Root + persistent flags (`--config`, `--no-color`, `--json`, `-v/--verbose`) |
| [`detectors_register.go`](../internal/cli/detectors_register.go) | n/a | **Registration choke point.** Blank-imports every detector leaf package — forget to add yours and the binary ships with an empty registry. |
| [`index.go`](../internal/cli/index.go) | `index [path]` | Scan → SQLite cache. Flags: `--batch-size`, `-w/--workers`. |
| [`enrich.go`](../internal/cli/enrich.go) | `enrich [path]` | Cache → Kuzu graph + FTS indexes. Flags: `--graph-dir`, `--memprofile`, `--max-buffer-pool`, `--copy-threads`. |
| [`mcp.go`](../internal/cli/mcp.go) | `mcp [path]` | Stdio MCP server. Flags: `--graph-dir`, `--max-results`, `--max-depth`, `--query-timeout`. |
| [`stats.go`](../internal/cli/stats.go) | `stats [path]` | Categorized graph statistics. Flags: `--category`, `--graph-dir`. |
| [`query.go`](../internal/cli/query.go) | `query <sub>` | Parent: `consumers`, `producers`, `callers`, `dependencies`, `dependents`. |
| [`find.go`](../internal/cli/find.go) | `find <sub>` | Parent: `endpoints`, `guards`, `entities`, `topics`, `queues`, `services`, `databases`, `components`. |
| [`cypher.go`](../internal/cli/cypher.go) | `cypher <query> [path]` | Raw read-only Cypher. Mutation gate rejects writes. |
| [`flow.go`](../internal/cli/flow.go) | `flow <view> [path]` | Architecture-flow diagrams (overview / ci / deploy / runtime / auth). |
| [`graph_cmd.go`](../internal/cli/graph_cmd.go) | `graph [path]` | Export full graph (json/yaml/mermaid/dot). |
| [`topology.go`](../internal/cli/topology.go) | `topology <sub>` | Parent: full map + `service-detail`, `blast-radius`, `bottlenecks`, `circular`, `dead`, `path`. |
| [`review.go`](../internal/cli/review.go) | `review [path]` | LLM-driven PR review via Ollama. |
| [`cache.go`](../internal/cli/cache.go) | `cache <sub>` | Parent: `info`, `list`, `inspect`, `clear`. |
| [`plugins.go`](../internal/cli/plugins.go) | `plugins <sub>` | Parent: `list`, `inspect`. |
| [`version.go`](../internal/cli/version.go) | `version` | Build info from [`internal/buildinfo`](../internal/buildinfo/). |

**No `config.go`** — the historical `codeiq config <action>` subcommand was never implemented. The root `--config` flag still loads `codeiq.yml`.

## `internal/analyzer/` — pipeline orchestration

| File | Role |
|---|---|
| [`analyzer.go`](../internal/analyzer/analyzer.go) | Index pipeline entry: FileDiscovery → parser → detectors → GraphBuilder → cache. `analyzer.Run()`. |
| [`enrich.go`](../internal/analyzer/enrich.go) | Enrich pipeline entry: cache → linkers → LayerClassifier → LexicalEnricher → LanguageEnricher → ServiceDetector → Kuzu BulkLoad. Tunable knobs: `EnrichOptions.StoreBufferPoolBytes`, `StoreCopyThreads`. |
| [`graph_builder.go`](../internal/analyzer/graph_builder.go) | Confidence-aware `mergeNode`, canonical edge dedup, deterministic `Snapshot()` (sorts + drops phantom edges). Nils internal maps after snapshot for memory hygiene (Phase A OOM fix). |
| [`file_discovery.go`](../internal/analyzer/file_discovery.go) | `git ls-files` first, dir-walk fallback. `DefaultExcludeDirs` skips `node_modules`, `vendor`, `target`, `.git`, etc. |
| [`service_detector.go`](../internal/analyzer/service_detector.go) | Walks the filesystem for build files (pom.xml, package.json, go.mod, Cargo.toml, pyproject.toml, …); emits one `SERVICE` node per module + `CONTAINS` edges. **Path-qualified IDs** (PR #151). |
| [`layer_classifier.go`](../internal/analyzer/layer_classifier.go) | Stamps Layer (frontend/backend/infra/shared/unknown) on every node. |
| [`linker/`](../internal/analyzer/linker/) | Cross-file linkers — TopicLinker, EntityLinker, ModuleContainmentLinker. |

## `internal/detector/` — 100 detectors

All implement [`detector.Detector`](../internal/detector/detector.go):
```go
type Detector interface {
    Name() string
    SupportedLanguages() []string
    DefaultConfidence() model.Confidence
    Detect(ctx *Context) *Result
}
```

Each registers itself in `init()` with `detector.RegisterDefault(NewMyDetector())`. The category subdirectory **must** also be blank-imported in [`internal/cli/detectors_register.go`](../internal/cli/detectors_register.go).

| Category | Path | Headcount (approx) |
|---|---|---|
| auth | `internal/detector/auth/` | OAuth/JWT/SSO scanners |
| frontend | `internal/detector/frontend/` | React, Vue, Svelte, Angular, routes |
| iac | `internal/detector/iac/` | Terraform, Bicep, Dockerfile, CloudFormation |
| jvm/java | `internal/detector/jvm/java/` | ~37 — Spring REST, Spring Security, ActiveMQ, gRPC, JPA, Quarkus, … |
| jvm/kotlin | `internal/detector/jvm/kotlin/` | Ktor routes, Kotlin structures |
| jvm/scala | `internal/detector/jvm/scala/` | Scala structures |
| python | `internal/detector/python/` | FastAPI, Flask, Django, SQLAlchemy, Pydantic |
| typescript | `internal/detector/typescript/` | TS / JS / Node frameworks |
| golang | `internal/detector/golang/` | gin, echo, chi, gRPC server |
| systems/cpp | `internal/detector/systems/cpp/` | C/C++ structures |
| systems/rust | `internal/detector/systems/rust/` | Rust / Cargo / actix / axum |
| csharp | `internal/detector/csharp/` | ASP.NET Core, EF Core, Azure SDK |
| markup | `internal/detector/markup/` | Markdown |
| proto | `internal/detector/proto/` | gRPC `.proto` files |
| sql | `internal/detector/sql/` | Migrations, raw SQL |
| structured | `internal/detector/structured/` | YAML, JSON, TOML, K8s, Helm, OpenAPI |
| script/shell | `internal/detector/script/shell/` | PowerShell, Bash |
| generic | `internal/detector/generic/` | Cross-language detectors (imports, references) |
| base | `internal/detector/base/` | **Not detectors.** Shared helpers — `RegexDetectorDefaultConfidence`, `StructuredDetectorDefaultConfidence`, `EnsureFileAnchor`, `EnsureExternalAnchor`, etc. Used by every detector category. |

Sample (Spring REST): [`internal/detector/jvm/java/spring_rest.go`](../internal/detector/jvm/java/spring_rest.go).

## `internal/graph/` — Kuzu facade

| File | Role |
|---|---|
| [`store.go`](../internal/graph/store.go) | Open / OpenReadOnly / OpenWithOptions. BufferPoolBytes default 2 GiB (`DefaultBufferPoolBytes`). |
| [`schema.go`](../internal/graph/schema.go) | Single `CodeNode` table + one REL table per `EdgeKind`. `ApplySchema()`. |
| [`bulk.go`](../internal/graph/bulk.go) | `BulkLoadNodes` / `BulkLoadEdges`. CSV staging with `DELIM='|', QUOTE='"', ESCAPE='"'`. Batched at 50k rows (env override `CODEIQ_BULK_BATCH_SIZE`). |
| [`cypher.go`](../internal/graph/cypher.go) | `Cypher(query, args)` and `CypherRows(query, args, maxRows)`. Mutation gate applies on read-only stores. |
| [`mutation.go`](../internal/graph/mutation.go) | `MutationKeyword(query)` returns the first blocked keyword (CREATE, DELETE, DETACH, SET, REMOVE, MERGE, DROP, FOREACH, LOAD CSV, COPY). CALL gate allow-lists `db.*`, `show_*`, `table_*`, `current_setting`, `table_info`, `query_fts_index`. |
| [`indexes.go`](../internal/graph/indexes.go) | `CreateIndexes()` builds two FTS indexes via `CALL CREATE_FTS_INDEX`. `SearchByLabel` / `SearchLexical` route through `QUERY_FTS_INDEX` with CONTAINS fallback for pre-enrich graphs. |
| [`reads.go`](../internal/graph/reads.go) | `Count`, `CountEdges`, `CountNodesByKind`, `CountNodesByLayer`, `FindByID`, `FindByKindPaginated`, `FindIncomingNeighbors`, etc. |

## `internal/cache/` — SQLite analysis cache

| File | Role |
|---|---|
| [`cache.go`](../internal/cache/cache.go) | `Open(path)`, transactional batch writes. |
| [`schema.go`](../internal/cache/schema.go) | 5 tables: `cache_meta` (reserved-word workaround uses `meta_key`/`meta_value`), `files`, `nodes`, `edges`, `analysis_runs`. `CacheVersion = 6`. |
| [`hasher.go`](../internal/cache/hasher.go) | Content hash for file-level dedup. |
| [`inspect.go`](../internal/cache/inspect.go) | Backend for `codeiq cache list / inspect`. |

## `internal/intelligence/`

| Subdir | Role |
|---|---|
| `extractor/` | `LanguageExtractor` interface + per-language impls (java, python, typescript, golang). Per-file tree-sitter parse, then walk to surface high-signal lexical features. |
| `lexical/` | LexicalEnricher + QueryService (FullTextStore interface). Populates `prop_lex_comment`, `prop_lex_config_keys`. Snippet extraction backs `evidence_pack` MCP mode. |

## `internal/mcp/`

| File | Role |
|---|---|
| [`server.go`](../internal/mcp/server.go) | `Server`, `Registry`, `Tool`. Uses `mcpsdk.Server.AddTool` / `mcpsdk.StdioTransport`. |
| [`tool.go`](../internal/mcp/tool.go) | `Tool` struct + JSON-RawMessage handler signature. |
| [`tools_consolidated.go`](../internal/mcp/tools_consolidated.go) | 6 mode-driven tools (`graph_summary`, `find_in_graph`, `inspect_node`, `trace_relationships`, `analyze_impact`, `topology_view`) + `review_changes`. Each delegates to underlying narrow handlers in `tools_graph.go` / `tools_intelligence.go` / `tools_topology.go`. |
| [`tools_graph.go`](../internal/mcp/tools_graph.go) | `run_cypher` + `read_file` user-facing tools + 18 narrow handlers (not user-facing). |
| [`tools_intelligence.go`](../internal/mcp/tools_intelligence.go) | Backing handlers for FTS-driven search modes. |
| [`tools_topology.go`](../internal/mcp/tools_topology.go) | Backing handlers for topology-view modes. |
| [`tools_flow.go`](../internal/mcp/tools_flow.go) | `generate_flow` tool (delegates to `internal/flow`). |
| [`tools_review.go`](../internal/mcp/tools_review.go) | `review_changes` tool — delegates to `internal/review`. |
| [`envelope.go`](../internal/mcp/envelope.go) | Error envelope helpers (`NewErrorEnvelope`, `RequestID`, `CodeInvalidInput`, etc.). |

## `internal/query/`

| File | Role |
|---|---|
| [`service.go`](../internal/query/service.go) | Service-level queries: `FindShortestPath`, `FindCycles`, `FindDeadCode`. |
| [`topology.go`](../internal/query/topology.go) | Topology projection — `Service`, `BlastRadius`, `Bottlenecks`, `Circular`. |
| [`stats.go`](../internal/query/stats.go) | Backing for `codeiq stats`. |

## `internal/flow/`, `internal/review/`, `internal/parser/`, `internal/model/`

| Package | Headline |
|---|---|
| [`internal/flow/`](../internal/flow/) | `Generate(view, format, store)` — 5 views (overview, ci, deploy, runtime, auth), 4 formats (json, mermaid, dot, yaml). |
| [`internal/review/`](../internal/review/) | `Client` (Ollama HTTP) + `ReviewService` (diff + graph evidence → LLM prompt → review JSON). Default base URL `http://localhost:11434`; cloud when `OLLAMA_API_KEY` set. |
| [`internal/parser/`](../internal/parser/) | `parser.Tree`, tree-sitter wrappers, structured parser for YAML/JSON/TOML/INI/properties. `ParseStructured` dispatches by language. |
| [`internal/model/`](../internal/model/) | Canonical types: `CodeNode`, `CodeEdge`, `NodeKind` (34 values), `EdgeKind` (28 values), `Confidence` (LEXICAL/SYNTACTIC/RESOLVED), `Layer` (frontend/backend/infra/shared/unknown). |
| [`internal/buildinfo/`](../internal/buildinfo/) | `Version`, `Commit`, `Date`, `Dirty`, `Platform`, `GoVersion`, `Features`. `init()` falls back to `runtime/debug.BuildInfo` when no `-ldflags -X`. |

## `testdata/`

| Path | Purpose |
|---|---|
| [`testdata/fixture-minimal/`](../testdata/fixture-minimal/) | 5-file fixture used by `index_test.go` and as a smoke target. **`README.md` is content** — it's part of the fixture, not project docs. |
| `testdata/fixture-multi-lang/` | Multi-service polyglot fixture used by the perf-gate workflow + multi-language enrich tests. |

## Why the directory structure matters

- **`internal/`** is Go-stdlib-enforced — nothing outside `github.com/randomcodespace/codeiq/...` can import from it. This is why every public surface (CLI subcommands, MCP tools) is a thin wrapper around `internal/` packages.
- **Detector registration is a choke point** ([`detectors_register.go`](../internal/cli/detectors_register.go)). The Go linker drops unimported packages even if they have `init()` functions — without the blank import, the detector ships dead.
- **One CodeNode table for all 34 NodeKinds** simplifies Cypher (no UNION over per-label tables) at the cost of label-index optimizations Kuzu could theoretically apply. See [02-architecture.md tradeoffs](02-architecture.md#important-tradeoffs).
