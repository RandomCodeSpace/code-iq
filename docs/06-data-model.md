# 06 — Data model

Two storage layers, both embedded:

| Layer | Engine | Path | Schema |
|---|---|---|---|
| Analysis cache | SQLite (`mattn/go-sqlite3` 1.14.44, WAL) | `<repo>/.codeiq/cache/codeiq.sqlite` | [`internal/cache/schema.go`](../internal/cache/schema.go) |
| Graph store | Kuzu 0.11.3 (`kuzudb/go-kuzu`) | `<repo>/.codeiq/graph/codeiq.kuzu/` | [`internal/graph/schema.go`](../internal/graph/schema.go) |

The cache is a content-addressable scratchpad for the index pipeline. The Kuzu store is the canonical fact base every read path consumes.

## SQLite analysis cache

`CacheVersion = 6`. PRAGMAs: `journal_mode=WAL`, `synchronous=NORMAL`, `foreign_keys=ON`, `busy_timeout=5000`. Schema lives in [`internal/cache/schema.go`](../internal/cache/schema.go).

### `cache_meta`
| Column | Type | Notes |
|---|---|---|
| `meta_key` | TEXT PRIMARY KEY | Reserved-word workaround (Java H2 parity; kept for byte-identical parity dumps). |
| `meta_value` | TEXT NOT NULL | Same workaround. |

### `files`
| Column | Type | Notes |
|---|---|---|
| `content_hash` | TEXT PRIMARY KEY | SHA-2-derived (see [`hasher.go`](../internal/cache/hasher.go)). |
| `path` | TEXT NOT NULL | Repo-relative file path. |
| `language` | TEXT NOT NULL | `java` / `python` / `typescript` / `go` / …. |
| `parsed_at` | TEXT NOT NULL | ISO-8601 timestamp. |
| `status` | TEXT | Default `'DETECTED'`. |
| `detection_method` | TEXT | Default `'tree-sitter'`. |
| `file_type` | TEXT | Default `'source'`. |
| `snippet` | TEXT | Inference: optional source excerpt. |

### `nodes`
| Column | Type | Notes |
|---|---|---|
| `row_id` | INTEGER PRIMARY KEY AUTOINCREMENT | |
| `id` | TEXT NOT NULL | Mirrors `CodeNode.id`. |
| `content_hash` | TEXT NOT NULL | FK to `files`. |
| `kind` | TEXT NOT NULL | `NodeKind` string. |
| `data` | TEXT NOT NULL | JSON-serialized `CodeNode`. |

Index: `idx_nodes_content_hash`.

### `edges`
| Column | Type | Notes |
|---|---|---|
| `source` | TEXT NOT NULL | Source node ID. |
| `target` | TEXT NOT NULL | Target node ID. |
| `content_hash` | TEXT NOT NULL | FK-ish back to file (no explicit FK constraint). |
| `kind` | TEXT NOT NULL | `EdgeKind` string. |
| `data` | TEXT NOT NULL | JSON-serialized `CodeEdge`. |

Index: `idx_edges_content_hash`.

### `analysis_runs`
| Column | Type | Notes |
|---|---|---|
| `run_id` | TEXT PRIMARY KEY | |
| `commit_sha` | TEXT | Optional git SHA at index time. |
| `timestamp` | TEXT NOT NULL | ISO-8601. |
| `file_count` | INTEGER NOT NULL | Files indexed in the run. |

Index: `idx_analysis_runs_timestamp`.

### Migrations

There is **no migration tool** — `CacheVersion = 6` is encoded as a constant in [`internal/cache/schema.go`](../internal/cache/schema.go) and stored in `cache_meta`. A version mismatch on open triggers a hard reset (Inference; verify in [`cache.go`](../internal/cache/cache.go)) — the recovery is to `rm -rf .codeiq/cache/`. Cache is regenerable from source.

## Kuzu graph store

Single node table `CodeNode` for all 34 NodeKinds. One REL table per `EdgeKind` (28 tables). All schema in [`internal/graph/schema.go`](../internal/graph/schema.go).

### `CodeNode` columns

| Column | Type | Purpose |
|---|---|---|
| `id` | STRING (PK) | Format `<prefix>:<filepath>:<type>:<identifier>` (e.g. `java:com/foo/Bar.java:class:Bar`). |
| `kind` | STRING | One of 34 `NodeKind` string values. |
| `label` | STRING | Display name (short identifier). |
| `fqn` | STRING | Fully-qualified name. |
| `file_path` | STRING | Source file path. |
| `line_start` | INT64 | Start line. Empty-string in CSV ⇒ NULL. |
| `line_end` | INT64 | End line. |
| `module` | STRING | Owning module/package. |
| `layer` | STRING | `frontend` / `backend` / `infra` / `shared` / `unknown`. |
| `language` | STRING | Source language. |
| `framework` | STRING | Framework stamp (e.g. `spring`, `quarkus`, `fastapi`). |
| `confidence` | STRING | `LEXICAL` / `SYNTACTIC` / `RESOLVED`. |
| `source` | STRING | Emitting detector name. |
| `label_lower` | STRING | `lower(label)`. Inference: kept for CONTAINS fallback. |
| `fqn_lower` | STRING | `lower(fqn)`. Same rationale. |
| `prop_lex_comment` | STRING | Doc-comment / docstring / JSDoc text — surfaced by [`intelligence/extractor`](../internal/intelligence/extractor/). FTS index 2 covers it. |
| `prop_lex_config_keys` | STRING | Surfaced config-key list (YAML/JSON keys). FTS index 2 covers it. |
| `props` | STRING | JSON-serialized catch-all property map. |

### REL tables (one per EdgeKind)

Every REL table has the shape `FROM CodeNode TO CodeNode` plus `id STRING, confidence STRING, source STRING, props STRING`. There are 28:

`DEPENDS_ON`, `IMPORTS`, `EXTENDS`, `IMPLEMENTS`, `CALLS`, `INJECTS`, `EXPOSES`, `QUERIES`, `MAPS_TO`, `PRODUCES`, `CONSUMES`, `PUBLISHES`, `LISTENS`, `INVOKES_RMI`, `EXPORTS_RMI`, `READS_CONFIG`, `MIGRATES`, `CONTAINS`, `DEFINES`, `OVERRIDES`, `CONNECTS_TO`, `TRIGGERS`, `PROVISIONS`, `SENDS_TO`, `RECEIVES_FROM`, `PROTECTS`, `RENDERS`, `REFERENCES_TABLE`.

### FTS indexes (Kuzu 0.11.3 native, BM25)

| Index name | Columns | Purpose |
|---|---|---|
| `code_node_label_fts` | `label`, `fqn_lower` | Powers `SearchByLabel` + `find_in_graph` text mode. |
| `code_node_lexical_fts` | `prop_lex_comment`, `prop_lex_config_keys` | Powers `SearchLexical` + doc/config-key search. |

Built at `codeiq enrich` time via [`graph.CreateIndexes()`](../internal/graph/indexes.go). Drop-then-create — idempotent across re-enrich.

## Canonical taxonomy (from `internal/model/`)

### NodeKind (34 values)

`NodeModule`, `NodePackage`, `NodeClass`, `NodeMethod`, `NodeEndpoint`, `NodeEntity`, `NodeRepository`, `NodeQuery`, `NodeMigration`, `NodeTopic`, `NodeQueue`, `NodeEvent`, `NodeRMIInterface`, `NodeConfigFile`, `NodeConfigKey`, `NodeWebSocketEndpoint`, `NodeInterface`, `NodeAbstractClass`, `NodeEnum`, `NodeAnnotationType`, `NodeProtocolMessage`, `NodeConfigDefinition`, `NodeDatabaseConnection`, `NodeAzureResource`, `NodeAzureFunction`, `NodeMessageQueue`, `NodeInfraResource`, `NodeComponent`, `NodeGuard`, `NodeMiddleware`, `NodeHook`, `NodeService`, `NodeExternal`, `NodeSQLEntity`.

### EdgeKind (28 values)

`EdgeDependsOn`, `EdgeImports`, `EdgeExtends`, `EdgeImplements`, `EdgeCalls`, `EdgeInjects`, `EdgeExposes`, `EdgeQueries`, `EdgeMapsTo`, `EdgeProduces`, `EdgeConsumes`, `EdgePublishes`, `EdgeListens`, `EdgeInvokesRMI`, `EdgeExportsRMI`, `EdgeReadsConfig`, `EdgeMigrates`, `EdgeContains`, `EdgeDefines`, `EdgeOverrides`, `EdgeConnectsTo`, `EdgeTriggers`, `EdgeProvisions`, `EdgeSendsTo`, `EdgeReceivesFrom`, `EdgeProtects`, `EdgeRenders`, `EdgeReferencesTable`.

### Confidence (ordered, integer-comparable)

| Constant | String | Score | Source pattern |
|---|---|---|---|
| `ConfidenceLexical` | `"LEXICAL"` | `0.6` | Regex / textual pattern only |
| `ConfidenceSyntactic` | `"SYNTACTIC"` | `0.8` | AST / parse-tree match |
| `ConfidenceResolved` | `"RESOLVED"` | `0.95` | Resolved via SymbolResolver (cross-file resolution) |

Merge rule (in `graph_builder.mergeNode`): the higher-confidence node wins on conflict; donor only fills properties the survivor doesn't already have.

### Layer (5 values)

`LayerFrontend` (`"frontend"`), `LayerBackend` (`"backend"`), `LayerInfra` (`"infra"`), `LayerShared` (`"shared"`), `LayerUnknown` (`"unknown"`).

Stamped by [`LayerClassifier`](../internal/analyzer/layer_classifier.go) during `enrich`. Detectors leave it at `LayerUnknown` unless they have strong evidence (e.g. a `tsx` component → `LayerFrontend`).

## ID conventions

| Prefix | Where | Example |
|---|---|---|
| `java:` / `py:` / `ts:` / `go:` / `cs:` / … | Per-language node IDs | `java:com/foo/Bar.java:class:Bar` |
| `<lang>:file:<path>` | File-anchor nodes | `py:file:scripts/check.py` |
| `<lang>:external:<name>` | External-anchor nodes for imports | `py:external:glob` |
| `service:<dir>:<name>` | SERVICE nodes (path-qualified after PR #151) | `service:frontend/widgets/checkbox:checkbox` |
| `topic:<name>` / `queue:<name>` / `event:<name>` | Cross-language messaging nodes | `topic:users.created` |
| `compose:<file>:service:<name>` | docker-compose services | `compose:docker-compose.yml:service:db` |
| `grpc:service:<protoFQN>` | gRPC services | `grpc:service:foo.v1.Greeter` |
| `proto:<file>:service:<name>` | protobuf services | |

ID format **matters** — the GraphBuilder dedup map keys off them, and the Kuzu BulkLoad COPY would abort on any duplicate primary key (this was the #151 bug). File-anchor + external-anchor nodes also exist specifically to keep imports edges from being dropped as "phantom" at snapshot.

## Persistence assumptions

- **Cache + graph are regenerable.** Both live under `.codeiq/`, gitignored, deletable at any time. `index` + `enrich` rebuild them.
- **No incremental enrich.** Today `enrich` does a full re-bulk-load. Per-file-incremental would require diffing against the existing graph; not implemented.
- **No retention / TTL.** Operator controls when to `rm -rf .codeiq/` to reset.
- **No multi-user / multi-tenant.** One indexed root per `.codeiq/` directory. Concurrent `codeiq enrich` on the same target would race.
- **Kuzu file format compatibility.** When you bump Kuzu, the on-disk format may change — Kuzu does not guarantee forward/backward compat across minor versions. Treat the graph store as **rebuildable from cache**, not a long-term archive. The cache uses SQLite, which is stable.

## What this model is **not**

- Not a code-search index (use `ripgrep`).
- Not an LSP server (use `gopls` / `pyright` / etc.).
- Not a dependency-resolution tool (use `go mod` / `pip` / `npm`).
- Not a SBOM tool (it gives a structural map, not a manifest).
- Not a compiler — detectors are pattern-matchers, not type-checkers. Cross-file resolution (`ConfidenceResolved`) is the closest it gets; that's intentional.
