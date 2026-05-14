# CLAUDE.md — codeiq

> **Repo-specific instructions for Claude Code (and any AI coding agent with similar tooling).** Read this in full before making changes. For the full doc set see [`docs/`](docs/); for the one-stop agent brief see [`docs/11-agent-handoff.md`](docs/11-agent-handoff.md).

## What this project is

A deterministic code-knowledge-graph CLI + stdio MCP server. Pure static analysis — no AI in the index/enrich pipeline. Single static Go binary. CGO mandatory.

- **Module path:** `github.com/randomcodespace/codeiq`
- **Entry:** [`cmd/codeiq/main.go`](cmd/codeiq/main.go) → [`internal/cli/root.go`](internal/cli/root.go)
- **Tech stack pinned in [`go.mod`](go.mod):** Go 1.25.10 toolchain, Kuzu 0.11.3, SQLite 1.14.44, MCP SDK v1.6, tree-sitter (smacker), cobra 1.10.2.

## Architecture in 10 lines

1. `codeiq index <path>` walks files (git ls-files → fallback), parses with tree-sitter / structured / regex, runs **100 detectors**, dedup-merges into a graph, and writes to **SQLite cache** at `.codeiq/cache/codeiq.sqlite`.
2. `codeiq enrich <path>` loads cache, runs linkers + LayerClassifier + intelligence extractors + ServiceDetector, then BulkLoads into **Kuzu** at `.codeiq/graph/codeiq.kuzu/` and builds two FTS indexes (`code_node_label_fts`, `code_node_lexical_fts`).
3. `codeiq mcp <path>` opens Kuzu read-only and serves a stdio JSON-RPC MCP protocol with **10 tools** (6 mode-driven + `run_cypher` + `read_file` + `generate_flow` + `review_changes`).
4. `codeiq review` is the only LLM touch — diff + graph evidence → Ollama (`localhost:11434` default; `OLLAMA_API_KEY` flips to cloud).
5. Every other subcommand (`stats`, `find`, `query`, `cypher`, `flow`, `graph`, `topology`, `cache`, `plugins`, `version`) is a thin read-only consumer of the Kuzu store.

## Critical rules

### Read-only MCP

The MCP server (`codeiq mcp`) is strictly read-only. `run_cypher` enforces this via [`MutationKeyword`](internal/graph/mutation.go) — regex gate that rejects CREATE/DELETE/DETACH/SET/REMOVE/MERGE/DROP/FOREACH/LOAD CSV/COPY and any CALL outside the allow-list (`db.*`, `show_*`, `table_*`, `current_setting`, `table_info`, **`query_fts_index`**). `read_file` is path-sandboxed to the indexed root.

Belt-and-braces: Kuzu is opened with `OpenReadOnly` at the engine level too.

### Determinism

Same input ⇒ same output, byte-for-byte. Every detector ships a determinism test. Conventions:

- Never iterate a Go `map` without sorting keys first.
- `GraphBuilder.Snapshot()` sorts nodes + edges by ID.
- Linker outputs go through `.Sorted()` at the call site.
- Detectors are stateless — no mutable struct fields. Method-local state only.

### Detector registration choke point

Adding a new detector under `internal/detector/<dir>/` is **not enough**. The package leaf must be blank-imported in [`internal/cli/detectors_register.go`](internal/cli/detectors_register.go). Without that line, the Go linker drops the package's `init()` and the binary ships with no registration for that detector family. This was the #1 silent-failure bug during the Java→Go port — 15 language families silently produced 0 nodes before the auto-import check was added to the dev workflow.

### Goroutine safety

- File I/O and detector dispatch run on a worker pool (`opts.Workers`, default `2 × GOMAXPROCS`).
- Detectors must be stateless. Method-local state only.
- Kuzu reads serialize behind the [`Store.mu`](internal/graph/store.go) mutex; one query at a time.
- The intelligence extractor pool is also `2 × GOMAXPROCS`-bounded to keep tree-sitter heap under control (Phase A OOM fix).

### Confidence ladder is monotonic

```
ConfidenceLexical    ("LEXICAL",   0.6)  — regex / textual pattern
ConfidenceSyntactic  ("SYNTACTIC", 0.8)  — AST / parse-tree match
ConfidenceResolved   ("RESOLVED",  0.95) — SymbolResolver cross-file resolution
```

In `mergeNode`, the higher-confidence node wins. The donor only fills properties the survivor doesn't already have (so a Spring detector's `framework=spring` stamp can't be overwritten by a generic detector's lower-confidence emission).

### Phantom edge drop

Edges with endpoints not in the node set get dropped at `Snapshot()`. Detectors emitting imports / depends-on edges across files must explicitly create the anchor nodes:

- `base.EnsureFileAnchor(ctx, lang)` — emits a `<lang>:file:<path>` node
- `base.EnsureExternalAnchor(ctx, lang, name)` — emits a `<lang>:external:<name>` node

See [`internal/detector/base/imports_helpers.go`](internal/detector/base/) and the gotcha note in [`docs/10-known-risks-and-todos.md`](docs/10-known-risks-and-todos.md).

## Build / test / run commands

```bash
# Build
CGO_ENABLED=1 go build -o /usr/local/bin/codeiq ./cmd/codeiq

# Test — full suite (884+ tests, ~30s)
CGO_ENABLED=1 go test ./... -count=1

# Race detector (CI-equivalent)
CGO_ENABLED=1 go test ./... -race -count=1

# Single package
CGO_ENABLED=1 go test ./internal/detector/jvm/java/... -count=1

# Static analysis (mirrors go-ci.yml)
go vet ./...
"$(go env GOPATH)/bin/staticcheck" ./...   # honnef.co/go/tools@2025.1.1
"$(go env GOPATH)/bin/gosec" -exclude=G104,G115,G202,G204,G301,G304,G306,G401,G404,G501 ./...
"$(go env GOPATH)/bin/govulncheck" ./...

# Smoke: index + enrich + stats on the canonical fixture
codeiq index testdata/fixture-minimal
codeiq enrich testdata/fixture-minimal
codeiq stats testdata/fixture-minimal

# MCP wiring for Claude Code / Cursor
codeiq mcp /path/to/repo
```

## Layout

```
codeiq/
├── cmd/codeiq/main.go     — entry; 5-line shim into internal/cli
├── cmd/extcheck/main.go   — build-time helper (Inference)
├── internal/
│   ├── analyzer/          — index + enrich pipelines, GraphBuilder, ServiceDetector
│   ├── buildinfo/         — Version/Commit/Date with debug.BuildInfo fallback
│   ├── cache/             — SQLite analysis cache (5 tables, CacheVersion=6)
│   ├── cli/               — cobra subcommands + detectors_register.go CHOKE POINT
│   ├── detector/          — 100 detectors organized by family
│   │   ├── auth/  csharp/  frontend/  generic/  golang/  iac/
│   │   ├── jvm/java/  jvm/kotlin/  jvm/scala/
│   │   ├── markup/  proto/  python/  script/shell/  sql/
│   │   ├── structured/  systems/{cpp,rust}/  typescript/
│   │   └── base/          — shared helpers (NOT detectors)
│   ├── flow/              — architecture-flow diagram engine
│   ├── graph/             — Kuzu facade + FTS + mutation gate
│   ├── intelligence/      — Lexical enricher + per-language extractors
│   ├── mcp/               — MCP server + 10 tools
│   ├── model/             — CodeNode, CodeEdge, NodeKind, EdgeKind, Confidence, Layer
│   ├── parser/            — tree-sitter + structured parsers
│   ├── query/             — service / topology / stats / dead-code Cypher templates
│   └── review/            — PR-review pipeline (diff + Ollama)
├── testdata/              — fixture-minimal, fixture-multi-lang
├── scripts/               — release / git-setup shell helpers
├── .github/workflows/     — go-ci, perf-gate, release-go, release-darwin, security, scorecard
├── .goreleaser.yml        — Goreleaser v2 (CGO multi-arch + Cosign + Syft)
├── go.mod / go.sum
├── docs/                  — Full reference doc tree (see docs/README equivalent in this file's sibling README.md)
├── CLAUDE.md              — this file
├── AGENTS.md              — short pointer to CLAUDE.md (Inference, may be regenerated)
└── README.md              — user-facing entry
```

## Gotchas (kept terse — full list in [`docs/10-known-risks-and-todos.md`](docs/10-known-risks-and-todos.md))

### Build / install

- **CGO mandatory.** `CGO_ENABLED=0` fails at link time. Kuzu, SQLite, tree-sitter all CGO.
- **Module is at repo root.** Post-PR-#162 hoist. Stale instructions saying `cd go && go build` are wrong.
- **`go install …@latest` may resolve to a poisoned version.** Deleted tags (`v0.1.0`, `v0.3.0`, `v1.0.0`) live on at `proxy.golang.org` with old layouts. Use an explicit `@v0.4.1` (or later never-previously-used version).

### Pipeline

- **Detector blank-import is mandatory.** Forget [`detectors_register.go`](internal/cli/detectors_register.go) and the family ships dead. `codeiq plugins list` is the quick check.
- **Determinism over all else.** Map iteration without sort = silent regression. Determinism tests will catch you.
- **Phantom edges drop at Snapshot.** Use `base.EnsureFileAnchor` / `EnsureExternalAnchor`.

### Kuzu 0.11.3 (current)

- **Native FTS bundled.** `INSTALL fts` is a no-op when bundled. `CALL CREATE_FTS_INDEX('<table>', '<name>', [cols])` + `CALL QUERY_FTS_INDEX('<table>', '<name>', '<query>')` work.
- **Parameterized `LIMIT $lim` / `SKIP $skip`** — use them. The old `fmt.Sprintf("LIMIT %d", n)` pattern is gone after PR #159.
- **`[]string` accepted directly for `IN $param`.** The old `stringsToAny` widener is gone (PR #159).
- **Mutation gate allow-lists `CALL QUERY_FTS_INDEX`.** Write-side `CREATE_FTS_INDEX` / `DROP_FTS_INDEX` stay blocked under `OpenReadOnly`.
- **Recursive pattern upper bound is still literal-only.** `[*1..N]` — `N` must be inline. We use `fmt.Sprintf` here; depth comes from a clamped `--max-depth` (default 10).
- **`EXISTS { … }` subqueries don't see outer-scope `$param`.** Inline static lists as rel-pattern alternations.
- **List comprehension on path nodes is broken.** Use `properties(nodes(p), 'id')`, not `[n IN nodes(p) | n.id]`.

### Bulk-load CSV

- **`DELIM='|'` + `QUOTE='"'` + `ESCAPE='"'`** in every Kuzu COPY. Required for RFC-4180 round-trip from Go's `csv.Writer`. Three production bugs in series taught us this (#150 commas, #153 pipes inside fields).
- **Service IDs are path-qualified.** `service:<dir>:<name>`. Two modules sharing a name don't collide on Kuzu PK (#151).

### TOML quoted keys

- **`unquote()` on both the key AND the section header.** Airflow's `.cherry_picker.toml` had `"check_sha" = "..."` which used to ship as `"check_sha"` (with quotes) into node IDs. Fixed in PR #152.

### MCP

- **MCP SDK v1.6 quirks:**
  - No `NewStdioTransport(in, out)` helper. `StdioTransport{}` zero-value binds `os.Stdin`/`os.Stdout`. Tests use `NewInMemoryTransports()`.
  - `Server.AddTool(t *Tool, h ToolHandler)` — two args, not aggregate.
  - `CallToolRequest.Params` is `*CallToolParamsRaw{Arguments json.RawMessage}`. The wrapper in [`internal/mcp/tool.go`](internal/mcp/tool.go) unmarshals once.
  - ToolHandler returns get JSON-marshaled by the SDK. **Special-case `string` returns** in `asSDKTool` so the Mermaid/DOT string from `generate_flow` doesn't double-encode.

### Release pipeline

- **`draft: true`** in `.goreleaser.yml` — every release lands as a draft, needs `gh release edit --draft=false`.
- **`release-darwin.yml` polls `release-go`** for 15 min with early-bail on upstream failure (PR #165 raised the budget from 90s).
- **Never re-use a deleted tag name.** `proxy.golang.org` caches version content immutably.

## Adding a new detector

1. Create `internal/detector/<family>/<name>.go`:
   ```go
   package <family>

   import (
       "github.com/randomcodespace/codeiq/internal/detector"
       "github.com/randomcodespace/codeiq/internal/detector/base"
       "github.com/randomcodespace/codeiq/internal/model"
   )

   type MyDetector struct{}

   func NewMyDetector() *MyDetector { return &MyDetector{} }

   func (MyDetector) Name() string                        { return "my_detector" }
   func (MyDetector) SupportedLanguages() []string        { return []string{"java"} }
   func (MyDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

   func init() { detector.RegisterDefault(NewMyDetector()) }

   func (MyDetector) Detect(ctx *detector.Context) *detector.Result {
       // pattern matching → return detector.ResultOf(nodes, edges) or detector.EmptyResult()
   }
   ```

2. If the family `<family>/` is **new** (no detector lived there before), blank-import it in [`internal/cli/detectors_register.go`](internal/cli/detectors_register.go).

3. Write `<name>_test.go` next to it. Three test cases required:
   - Positive match
   - Negative match (avoids false positives)
   - Determinism (run twice, assert byte-identical output)

4. `CGO_ENABLED=1 go test ./internal/detector/<family>/... -count=1`

5. Smoke check: `codeiq plugins list | grep my_detector` should show the new detector.

## Adding a new MCP tool mode

If extending one of the 6 consolidated tools with a new mode:

1. Edit the relevant tool builder in [`internal/mcp/tools_consolidated.go`](internal/mcp/tools_consolidated.go).
2. Add a parity-test entry in [`internal/mcp/tools_consolidated_parity_test.go`](internal/mcp/tools_consolidated_parity_test.go) covering arg-name mapping to the underlying narrow handler.
3. Update [`docs/04-main-flows.md`](docs/04-main-flows.md) MCP tool table.

If adding a wholly new top-level MCP tool:

1. Add a `toolXxx(d) Tool` builder somewhere under `internal/mcp/`.
2. Register it in `RegisterGraphUserFacing` / `RegisterConsolidated` / `RegisterFlow` (in [`internal/cli/mcp.go`](internal/cli/mcp.go) → `registerAllTools`).
3. Write an integration test in [`internal/mcp/integration_test.go`](internal/mcp/integration_test.go).

## Permission discipline

- **Never commit unless the user explicitly asks.** Agent-generated `*.md` files (plans, scratchpad) must be in `.gitignore` before any push.
- **Never push to `main` directly.** Always via PR.
- **Never bypass branch protection** with `gh pr merge --admin`. `go-ci.yml` and `security.yml` are required for a reason.
- **Never `git tag --force` a deleted version name.** `proxy.golang.org` cache poison.
- **Always use `t.TempDir()` in tests.** No test should write outside its tempdir.
- **For destructive ops** (`rm -rf`, `git push --delete`, `gh release delete`, `git reset --hard`): ask before doing, unless the operator explicitly authorized.

## When in doubt

- Read [`docs/11-agent-handoff.md`](docs/11-agent-handoff.md).
- Run the smoke test on `testdata/fixture-minimal` after any pipeline change.
- Use `git log -p --since="1 month"` to learn the recent change pattern.
- The user values terse output. Skip preamble. Show the change + verification command.
