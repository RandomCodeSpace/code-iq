# codeiq (Go) — Project Instructions

## What This Project Is

**codeiq** — a CLI tool + MCP server that scans codebases to build a
deterministic code knowledge graph. No AI, no external APIs — pure
static analysis. 100 detectors, 35+ languages, Kuzu embedded graph
database, MCP stdio server, single static Go binary.

- **CLI command**: `codeiq` (single binary from `go/cmd/codeiq/main.go`)
- **Go module**: `github.com/randomcodespace/codeiq/go`
- **Go directive**: `go 1.25.0` (dep-mandated by `modelcontextprotocol/go-sdk`); `toolchain go1.25.10`
- **GitHub repo**: `RandomCodeSpace/codeiq` (default branch: `main`)
- **Cache on disk**: `.codeiq/cache/codeiq.sqlite` (SQLite analysis cache)
- **Graph on disk**: `.codeiq/graph/codeiq.kuzu` (Kuzu embedded graph)
- **Config file**: `codeiq.yml` (project-level overrides)

The Java/Spring Boot reference that seeded this codebase was deleted
in Phase 6 cutover (v0.3.0). For history, see commits `c363727` (port
landing) and `c630245` (release infra).

## Tech Stack

> Source of truth: `go/go.mod` + `go/go.sum`. Update pins there; this
> list moves with them in the same commit.

- **Go 1.25.10** — toolchain pin; module min is 1.25.0 (clamped by the
  MCP SDK's own `go` directive).
- **Kuzu 0.7.1** (`github.com/kuzudb/go-kuzu`) — embedded graph DB.
  CGO. v0.7.1 quirks documented in `## Gotchas` below.
- **`mattn/go-sqlite3` 1.14.22** — SQLite analysis cache. CGO.
- **`smacker/go-tree-sitter`** — AST parsing for Java / Python /
  TypeScript / Go.
- **`modelcontextprotocol/go-sdk` v1.6** — stdio MCP server. v1.6 API
  shape: `Server.Serve(ctx, mcpsdk.Transport)`; no `NewStdioTransport`
  helper.
- **`spf13/cobra`** — CLI framework. Subcommand registration via
  `internal/cli` blank imports.

## Architecture

### Pipeline

```
index:   FileDiscovery → Parsers → Detectors (goroutine pool) → GraphBuilder → SQLite cache
enrich:  SQLite → Linkers → LayerClassifier → LexicalEnricher → LanguageEnricher → ServiceDetector → Kuzu (COPY FROM)
mcp:     Kuzu → QueryService → 6 consolidated MCP tools + run_cypher escape hatch + review_changes
```

codeiq has no REST API and no web UI surface — by design. Consumers
interact through the CLI or through the stdio MCP server (read-only).
The Java reference had a `codeiq serve` subcommand (Spring Boot REST
+ React SPA); both were removed in the Go port and will not be
reintroduced.

### Pipeline components

- **`internal/analyzer/file_discovery.go`** — `git ls-files` first,
  dir-walk fallback. Maps extension → `parser.Language` via
  `LanguageFromExtension` in `internal/parser/parser.go`.
- **`internal/parser`** — tree-sitter wrappers + a structured parser
  for YAML/JSON/TOML/INI/properties. Falls back to regex-only when
  parse fails (matches Java's per-file try/catch).
- **`internal/detector`** — `@Component` analogue is Go's `init()`
  blank-import pattern; every detector registers itself with
  `detector.Default`. Auto-discovery via `internal/cli/detectors_register.go`
  (this file is the choke point — every detector package leaf must
  blank-import here or the binary won't fire it).
- **`internal/analyzer/graph_builder.go`** — buffers detector results.
  Confidence-aware node merge (`mergeNode`), canonical
  `(source, target, kind)` edge dedup, deterministic Snapshot with
  dangling-edge drop. Surfaces dedup/drop counts on `Stats`.
- **`internal/analyzer/linker/`** — TopicLinker, EntityLinker,
  ModuleContainmentLinker. Each emits `Result{Nodes, Edges}` that's
  `.Sorted()` at the call site (Phase 1 §1.4).
- **`internal/graph`** — Kuzu wrapper. Read-only via `OpenReadOnly`
  (mutation gate in `cypher.go`).
- **`internal/mcp`** — 6 consolidated mode-driven tools (`graph_summary`,
  `find_in_graph`, `inspect_node`, `trace_relationships`,
  `analyze_impact`, `topology_view`), `run_cypher` escape hatch,
  `read_file` utility, `generate_flow`, and `review_changes` — 10
  user-facing tools total. The narrow toolXxx(d) builder funcs remain
  in tools_graph.go/tools_intelligence.go/tools_topology.go as Go-API
  delegation targets for the consolidated layer; they are NOT
  registered as user-facing MCP tools.
- **`internal/review`** — diff parser, Ollama-compatible chat client,
  ReviewService orchestrator. Default endpoint = local Ollama;
  `OLLAMA_API_KEY` flips to Ollama Cloud.

### Package layout

```
go/
├── cmd/codeiq/                 # main package — single binary entrypoint
├── internal/
│   ├── analyzer/               # pipeline orchestration
│   │   └── linker/             # cross-file enrichers
│   ├── buildinfo/              # version/commit/date from -ldflags
│   ├── cache/                  # SQLite analysis cache
│   ├── cli/                    # cobra subcommands + detector registrations
│   ├── detector/               # 100 detectors organized by category
│   │   ├── auth/
│   │   ├── base/               # AbstractDetector analogues + helpers
│   │   ├── csharp/
│   │   ├── frontend/           # React, Vue, Svelte, Angular, frontend routes
│   │   ├── generic/
│   │   ├── golang/
│   │   ├── iac/                # Terraform, Bicep, Dockerfile, CloudFormation
│   │   ├── jvm/
│   │   │   ├── java/           # ~37 Java detectors
│   │   │   ├── kotlin/
│   │   │   └── scala/
│   │   ├── markup/             # Markdown
│   │   ├── proto/
│   │   ├── python/
│   │   ├── script/shell/       # PowerShell, Bash
│   │   ├── sql/                # SqlMigration
│   │   ├── structured/         # YAML, JSON, TOML, K8s, Helm, OpenAPI, …
│   │   ├── systems/{cpp,rust}/
│   │   └── typescript/
│   ├── flow/                   # architecture-flow diagram engine
│   ├── graph/                  # Kuzu facade
│   ├── intelligence/           # Lexical + language extractors + evidence + query planner
│   ├── mcp/                    # MCP server + tool definitions
│   ├── model/                  # CodeNode, CodeEdge, NodeKind, EdgeKind, Confidence, Layer
│   ├── parser/                 # tree-sitter + structured parsers
│   ├── query/                  # service / topology / stats
│   └── review/                 # PR-review pipeline (diff + LLM)
├── parity/                     # parity harness (build tag `parity`)
├── testdata/                   # fixtures
├── go.mod
└── go.sum
```

## Critical Rules

### Read-Only MCP

The MCP server is **strictly read-only** — no data mutation from tool
calls. `run_cypher` rejects mutation keywords at the gate
(`internal/graph/cypher.go`). `review_changes` reads the graph and
shells out to `git`; it never writes to `.codeiq/`.

Analysis/enrichment happens only via the CLI commands `index` /
`enrich`.

### Determinism

- Same input MUST produce same output. Every run.
- No `map` iteration without sorting first (every range loop over a
  map sorts keys before emit).
- `GraphBuilder.Snapshot` sorts nodes + edges by ID.
- Linker outputs go through `Result.Sorted()` at the boundary.
- All detectors are stateless — no mutable struct fields. Stateless
  methods only. The single shared instance per detector type is
  registered with `detector.Default` at package init.

### Detector dispatch is choke-pointed

Adding a new detector package under `internal/detector/<dir>/` is NOT
enough. The package must be blank-imported in
[`internal/cli/detectors_register.go`](go/internal/cli/detectors_register.go).
Without that line, the package's `init()` never runs and the binary
ships without your detector. The Phase 4 benchmark exposed this bug
when 15 language families silently produced 0 nodes — see commit
`04098be` for the fix.

### Goroutine safety

- File I/O and SQLite writes run on a bounded worker pool
  (`Analyzer.opts.Workers`, default 2× GOMAXPROCS).
- Detectors must be stateless. Method-local state only.
- Kuzu reads use the embedded API; one query at a time per
  `Store.Cypher` call. The store internal mutex serializes.

## CLI Commands

| Command | Purpose |
|---|---|
| `index [path]` | Scan files → SQLite analysis cache. |
| `enrich [path]` | Load cache → Kuzu graph; run linkers + LayerClassifier + intelligence. |
| `mcp [path]` | Stdio MCP server (Claude / Cursor). |
| `stats [path]` | Categorized statistics from the enriched graph. |
| `query <kind> <id> [path]` | consumers/producers/callers/dependencies/dependents/shortest-path/cycles/dead-code. |
| `find <preset> [path]` | endpoints, entities, services, … |
| `cypher <query> [path]` | Raw Cypher (read-only) against Kuzu. |
| `flow [path]` | Architecture-flow diagrams (mermaid/dot/yaml). |
| `graph [path]` | Export graph in json / yaml / mermaid / dot. |
| `topology [path]` | Service-topology projection. |
| `review [path]` | LLM-driven PR review (Ollama by default). |
| `cache <action>` | Inspect / clear the SQLite cache. |
| `plugins <action>` | List + describe registered detectors. |
| `config <action>` | Validate / explain `codeiq.yml`. |
| `version` | `--version` long form. |

### Standard pipeline

```bash
codeiq index /path/to/repo
codeiq enrich /path/to/repo
codeiq stats /path/to/repo
codeiq mcp /path/to/repo                # for Claude / Cursor wiring
```

## MCP Tools

The MCP server registers 10 user-facing tools — 6 consolidated
mode-driven, `run_cypher` (escape hatch), `read_file` (utility),
`generate_flow`, and `review_changes`. The 24 narrow tools that the
consolidated layer subsumes were dropped from the MCP surface;
their Go-API implementations (`toolXxx(d) Tool`) stay in the package
because the consolidated tools delegate to them.

| Consolidated tool | mode dispatch |
|---|---|
| `graph_summary` | `overview` / `categories` / `capabilities` / `provenance` |
| `find_in_graph` | `nodes` / `edges` / `text` / `fuzzy` / `by_file` / `by_endpoint` |
| `inspect_node` | `neighbors` / `ego` / `evidence` / `source` |
| `trace_relationships` | `callers` / `consumers` / `producers` / `dependencies` / `dependents` / `shortest_path` |
| `analyze_impact` | `blast_radius` / `trace` / `cycles` / `circular_deps` / `dead_code` / `dead_services` / `bottlenecks` |
| `topology_view` | `summary` / `service` / `service_deps` / `service_dependents` / `flow` |
| `run_cypher` | (escape hatch — mutation-rejected) |
| `review_changes` | (Ollama-driven PR review) |

## Adding a New Detector

1. Create file in `go/internal/detector/<category>/my_detector.go`.
2. Implement the `detector.Detector` interface:

   ```go
   package mycategory

   import (
       "github.com/randomcodespace/codeiq/go/internal/detector"
       "github.com/randomcodespace/codeiq/go/internal/detector/base"
       "github.com/randomcodespace/codeiq/go/internal/model"
   )

   type MyDetector struct{}

   func NewMyDetector() *MyDetector { return &MyDetector{} }

   func (MyDetector) Name() string                        { return "my_detector" }
   func (MyDetector) SupportedLanguages() []string        { return []string{"java"} }
   func (MyDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

   func init() { detector.RegisterDefault(NewMyDetector()) }

   func (MyDetector) Detect(ctx *detector.Context) *detector.Result {
       // … pattern matching → return detector.ResultOf(nodes, edges)
       return detector.EmptyResult()
   }
   ```

3. **CRITICAL** — if the package is a NEW directory under
   `internal/detector/`, blank-import it in
   `go/internal/cli/detectors_register.go`. Existing directories
   already covered.
4. Add a test file at the same path (`my_detector_test.go`). Include
   positive match, negative match, determinism (run twice, assert
   identical output).
5. `cd go && CGO_ENABLED=1 go test ./internal/detector/<category>/...
   -count=1`.

### Detector base helpers

| File | Purpose |
|---|---|
| `base/regex.go` | `FindLineNumber`, `RegexDetectorDefaultConfidence`. |
| `base/imports_helpers.go` | `EnsureFileAnchor`, `EnsureExternalAnchor` — emit anchor nodes so imports/depends_on edges survive `Snapshot`'s phantom filter. |
| `base/component.go` | `CreateComponentNode` for React/Vue/Angular component detectors. |
| `base/structures.go` | `AddImportEdge`, `CreateStructureNode` for Scala/Kotlin/etc structure detectors. |

## Configuration

`codeiq.yml` at the repo root. Resolution order (last wins):

1. Built-in defaults
2. `~/.codeiq/config.yml`
3. `./codeiq.yml`
4. `CODEIQ_<SECTION>_<KEY>` env vars
5. CLI flags

`codeiq config validate` + `codeiq config explain`.

## Testing

```bash
cd go

# Full suite
CGO_ENABLED=1 go test ./... -count=1

# Race detector
CGO_ENABLED=1 go test ./... -race -count=1

# Single package
CGO_ENABLED=1 go test ./internal/detector/jvm/java/...

# Verbose
CGO_ENABLED=1 go test ./... -v
```

828+ tests. Every detector ships with positive, negative, and
determinism tests.

## Build Commands

```bash
cd go

# Build
CGO_ENABLED=1 go build -o /usr/local/bin/codeiq ./cmd/codeiq

# Build with version info (release-go.yml does this with goreleaser):
CGO_ENABLED=1 go build \
  -ldflags "-X 'github.com/randomcodespace/codeiq/go/internal/buildinfo.Version=v0.3.0' \
            -X 'github.com/randomcodespace/codeiq/go/internal/buildinfo.Commit=$(git rev-parse --short HEAD)' \
            -X 'github.com/randomcodespace/codeiq/go/internal/buildinfo.Date=$(date -u +%Y-%m-%dT%H:%M:%SZ)'" \
  -o /usr/local/bin/codeiq ./cmd/codeiq
```

Release pipeline:
[`shared/runbooks/release-go.md`](shared/runbooks/release-go.md).

## Code Conventions

- Go 1.25+ idioms — generics where they reduce repetition, `slices.`
  and `maps.` over hand-rolled loops, `min`/`max` builtins.
- `model.Confidence` and `Source` are mandatory on every `CodeNode` /
  `CodeEdge`. Base classes stamp the per-detector floor at the
  orchestration boundary (LEXICAL for regex bases, SYNTACTIC for
  AST/structured bases).
- Property union semantics: in `mergeNode`, donor only fills keys the
  survivor doesn't already have. Don't clobber a high-confidence
  detector's framework/auth_type stamping.
- ID format: `<prefix>:<filepath>:<type>:<identifier>` — keep prefixes
  stable; the GraphBuilder dedup map relies on them.
- File-anchor / external-anchor IDs:
  - `<lang>:file:<path>` for the file-as-module
  - `<lang>:external:<name>` for imported packages
  This pattern saves imports edges from phantom drop.
- Detectors with framework guards: require a framework-specific
  import before emitting (e.g. Quarkus requires `io.quarkus`).
- UTF-8 everywhere — explicit `[]byte` only when interfacing with
  Kuzu or SQLite.

## Gotchas & Lessons Learned

### Pipeline

- **Pipeline is `index → enrich → (mcp|stats|query)`.** Don't put
  analysis in MCP. MCP is read-only.
- **Detector registration choke point** (`internal/cli/detectors_register.go`).
  Forgetting the blank import ships an empty registry for that
  language. Caught by the polyglot benchmark — 15 language families
  silently produced 0 nodes pre-fix. Test: `codeiq plugins` lists
  every detector by name; new ones must appear.

### Kuzu v0.7.1 quirks

- FTS extension not bundled, not downloadable offline. `INSTALL fts`
  errors with "fts is not an official extension". `CreateIndexes()`
  no-ops FTS; `SearchByLabel` / `SearchLexical` use case-insensitive
  `CONTAINS` predicates.
- LIMIT / SKIP can't be parameterized. Inline as literals;
  parameterize the needle only.
- Uses `lower()` (SQL) not `toLower()` (openCypher).
- `RETURN DISTINCT` scope tighter than openCypher; `ORDER BY` must
  reference the projected alias, not the bound variable.
- List comprehension binder rejects out-of-scope variables. Use
  `properties(nodes(p), 'id')` instead of `[n IN nodes(p) | n.id]`.
- `EXISTS { … }` subquery doesn't see outer-scope `$param`. Inline
  static lists as rel-pattern alternations.
- Go binding's `goValueToKuzuValue` accepts `[]any` only. Added
  `stringsToAny` widener for `IN $param` use cases.
- Multi-label rel alternation + kleene-star in the same recursive
  pattern breaks the binder. BlastRadius uses an anonymous recursive
  pattern.

### MCP SDK v1.6

- No `NewStdioTransport(in, out)` helper. `StdioTransport{}`
  zero-value bound to `os.Stdin`/`os.Stdout`. Tests use
  `NewInMemoryTransports()`.
- `Server.AddTool(t *Tool, h ToolHandler)` — two args, not aggregate.
- `CallToolRequest.Params` is `*CallToolParamsRaw{Arguments
  json.RawMessage}`. Wrapper unmarshals once, hands raw JSON to the
  handler.
- ToolHandler JSON-marshals returned values. Special-case `string`
  in `mcp/tool.go` for the `generate_flow` rendered output —
  otherwise the Mermaid/DOT string gets double-encoded.

### Go RE2 vs Java regex

- No lookahead / lookbehind. Plan-spec patterns like
  `CALL\s+(?!db\.)` won't compile. Rewrites: two-stage match (collect
  every CALL site, then allow-list each procedure name).
- No possessive quantifiers (`*+`). RE2 doesn't need them — its NFA
  doesn't backtrack. Strip them when porting Java regex.
- No DOTALL — use `(?s)` prefix.

### Detector authoring traps

- **Phantom edges**: emit edges with anchor nodes on both ends
  (`base.EnsureFileAnchor` + `base.EnsureExternalAnchor`). Without
  anchors, the edge drops at Snapshot.
- **Discriminator guards**: framework detectors must require a
  framework-specific import or annotation before emitting. Without a
  guard, generic patterns (e.g. `@Transactional`) match across
  unrelated frameworks and produce false positives.
- **Determinism**: never iterate a Go `map` without sorting keys
  first. Run the determinism test twice with `count=1` to catch this.

### Filesystem & paths

- File discovery dir-walk fallback ingests `node_modules/`,
  `vendor/`, `target/`, etc. — see `DefaultExcludeDirs` in
  `analyzer/file_discovery.go`. Add new ignored dirs there.
- `Files.probeContentType` is best-effort on Linux (JDK note from the
  Java side — replaced in Go by `net/http.DetectContentType` plus an
  explicit allowlist in `mcp/read_file.go`).

### Performance

- CertificateAuthDetector once consumed 99% of indexing CPU on
  C#-heavy projects because its pre-screen included `.cert` / `.crt`
  / `.pem` substrings that match `using
  System.Security.Cryptography.X509Certificates;`. Use a STRICT
  keyword list (high-signal markers only — not path extensions) in
  any cross-language regex pre-screen.

### Release / signing

- Release tag must be `v*.*.*`; pre-releases use the
  `vX.Y.Z-rc.N` form (Goreleaser `prerelease: auto` honors it).
- Cosign keyless via GitHub OIDC — no long-lived key on the runner.
  Verification needs the cosign bundle file + the OIDC identity regex
  (see `shared/runbooks/release-go.md`).

## Updating This File

After significant changes (new detectors, new MCP tools, architectural
decisions, conventions learned), update this file. Keep it concise.
The full pre-cutover Java-side history of these notes is on the
squash-merge `c363727`; reach for that via `git show` when you need
context.
