# Data Model

codeiq's data model has **three storage layers**, each with its own schema and lifetime:

| Layer | Backing | Purpose | Lifetime |
|---|---|---|---|
| Domain types | Java records / enums | In-memory shape of nodes/edges, single source of truth | Per JVM run |
| Analysis cache | H2 (file-backed, embedded) | Per-file detection results keyed by content hash; enables incremental re-indexing | `.codeiq/cache/` until manually cleared or `CACHE_VERSION` bump |
| Graph | Neo4j Embedded (Community Edition 2026.02.3) | Final enriched graph for queries, MCP tools, REST API | `.codeiq/graph/graph.db/` until manually cleared |

## Storage

### Primary datastore — Neo4j Embedded
- **Defined in:** `pom.xml` `<neo4j.version>2026.02.3</neo4j.version>`, bootstrapped in `config/Neo4jConfig.java` (only loaded under the `serving` profile via `@ConditionalOnProperty(value="codeiq.neo4j.enabled", havingValue="true")`).
- **Data dir:** `.codeiq/graph/graph.db/` inside the scanned repo.
- **Migration tool:** none — Neo4j is schemaless; indexes/constraints are created idempotently by `GraphStore.bulkSave()`.

### Secondary datastore — H2 (analysis cache)
- **Defined in:** `cache/AnalysisCache.java`. H2 is a transitive Spring Boot dependency (no explicit version pin in `pom.xml`).
- **Data dir:** `.codeiq/cache/` inside the scanned repo.
- **Schema versioning:** `CACHE_VERSION = 4` constant near the top of `AnalysisCache.java` (currently line 43; grep the symbol if drifted). On startup, cache reads the stored version; if it doesn't match, the H2 file is wiped and recreated. **Bump `CACHE_VERSION` whenever you change the file-hash algorithm or the schema.**

## Domain types

### `CodeNode` and `CodeEdge`
- **Defined in:** `model/CodeNode.java`, `model/CodeEdge.java`.
- **Plain Java records / classes** (not JPA entities — Spring Data Neo4j is used only on the write path). Properties live in a `Map<String, Object>`.
- **ID format:** `"{prefix}:{filepath}:{type}:{identifier}"` (e.g. `"node:src/main/java/Foo.java:class:Foo"`). Cross-file uniqueness is enforced by including the full file path. See existing detectors for the prefix convention.

### `NodeKind` (enum)
- **Defined in:** `model/NodeKind.java`.
- **34 concrete values** (javadoc and file are in sync as of 2026-04-27):

```
MODULE, PACKAGE, CLASS, METHOD, ENDPOINT, ENTITY, REPOSITORY, QUERY,
MIGRATION, TOPIC, QUEUE, EVENT, RMI_INTERFACE, CONFIG_FILE, CONFIG_KEY,
WEBSOCKET_ENDPOINT, INTERFACE, ABSTRACT_CLASS, ENUM, ANNOTATION_TYPE,
PROTOCOL_MESSAGE, CONFIG_DEFINITION, DATABASE_CONNECTION, AZURE_RESOURCE,
AZURE_FUNCTION, MESSAGE_QUEUE, INFRA_RESOURCE, COMPONENT, GUARD,
MIDDLEWARE, HOOK, SERVICE, EXTERNAL, SQL_ENTITY
```

Each enum constant carries a lowercase `value` (e.g. `CLASS("class")`) used as the string representation in Cypher / JSON / MCP-tool responses. `NodeKind.fromValue(...)` does the reverse lookup via a static `BY_VALUE` map.

### `EdgeKind` (enum)
- **Defined in:** `model/EdgeKind.java`.
- **28 concrete values** (javadoc and file are in sync as of 2026-04-27):

```
DEPENDS_ON, IMPORTS, EXTENDS, IMPLEMENTS, CALLS, INJECTS, EXPOSES,
QUERIES, MAPS_TO, PRODUCES, CONSUMES, PUBLISHES, LISTENS, INVOKES_RMI,
EXPORTS_RMI, READS_CONFIG, MIGRATES, CONTAINS, DEFINES, OVERRIDES,
CONNECTS_TO, TRIGGERS, PROVISIONS, SENDS_TO, RECEIVES_FROM, PROTECTS,
RENDERS, REFERENCES_TABLE
```

### `layer` (string property, not an enum)
Every node carries a `layer` property set by `analyzer/LayerClassifier.java` to one of: `frontend`, `backend`, `infra`, `shared`, `unknown`. Classification is deterministic — based on `kind`, `framework`, and path heuristics.

## H2 cache schema

Defined in the `SCHEMA_SQL` text block near the top of `cache/AnalysisCache.java` (grep `SCHEMA_SQL`). Tables (verified from the file):

| Table | Purpose |
|---|---|
| `cache_meta` | `meta_key` (PK) → `meta_value` — stores the `version` row matching `CACHE_VERSION` |
| `files` | `content_hash` (PK) → file path, language, size, parse timestamp; the unit of cache lookup |
| `nodes` | per-file detected nodes; `row_id` AUTO_INCREMENT PK; FK to `files.content_hash` |
| `edges` | per-file detected edges; FK to `files.content_hash` |
| `analysis_runs` | `run_id` (PK), wall-clock metadata for one `index`/`analyze` invocation |

**Reserved-word note:** H2 reserves `key`, `value`, `order`. The schema uses `meta_key` / `meta_value` etc. — keep that pattern when extending.

**Concurrency:** the cache uses a `ReentrantReadWriteLock` (`AnalysisCache.java`). Many virtual-thread readers can run in parallel; writers serialize. This is what avoids `ClosedChannelException` against H2's MVStore file channel under concurrent virtual-thread access.

## Neo4j schema (created by `GraphStore.bulkSave`)

Indexes created idempotently (`CREATE … IF NOT EXISTS`) inside `GraphStore.bulkSave()` (`graph/GraphStore.java`, around lines 112–122 at time of writing — grep `CREATE INDEX` to relocate):

| Index | Type | Property |
|---|---|---|
| (unnamed) | b-tree | `(:CodeNode {id})` |
| (unnamed) | b-tree | `(:CodeNode {label_lower})` |
| (unnamed) | b-tree | `(:CodeNode {fqn_lower})` |
| `search_index` | fulltext | `[label_lower, fqn_lower]` over `:CodeNode` |
| `lexical_index` | fulltext | `[prop_lex_comment, prop_lex_config_keys]` over `:CodeNode` |

The `CLAUDE.md` "Gotchas" section additionally references b-tree indexes on `kind`, `layer`, `module`, `filePath`. **Cross-check before relying on those** — `grep "CREATE INDEX" graph/GraphStore.java` shows only the 3 above plus the 2 fulltext indexes. The CLAUDE.md claim may be aspirational or stale.

### Property round-trip convention

Domain `properties` Map → Neo4j stored as `prop_<key>` properties. Domain ID, layer, kind, etc. become top-level node properties (`id`, `layer`, `kind`, `label_lower`, `fqn_lower`, `module`, `filePath`). The reverse mapping is in `nodeFromNeo4j()` inside `graph/GraphStore.java`. **Whenever you add a domain property, verify the round-trip survives** — silent property loss is the most common bug class on this seam.

### Bulk-save batching

`bulkSave` uses `UNWIND $batch AS props CREATE (n:CodeNode) SET n = props` for nodes (default batch 500) and a similar UNWIND-MATCH-MATCH-CREATE pattern for edges. Edge UNWIND **silently drops rows whose source/target node IDs are missing** — pre-validate before passing in. See [`CLAUDE.md`](../../CLAUDE.md) §"Gotchas".

## Lifecycle / state machines

There are no state machines on entities themselves. The closest thing is the **pipeline lifecycle** that produces them:

```
file on disk
   ─► hashed (SHA-256, FileHasher.java)
        ─► H2 cache lookup
            ├─ hit  → reuse cached nodes/edges
            └─ miss → run detectors, write nodes+edges keyed by content_hash
                        ─► H2 cache populated

(later, on `enrich`:)
   ─► H2 read
       ─► UNWIND bulk-load to Neo4j
           ─► linkers (Topic, Entity, ModuleContainment, Guard) add cross-file edges
               ─► LayerClassifier sets layer property on every node
                   ─► ServiceDetector adds SERVICE nodes + CONTAINS edges
                       ─► LanguageEnricher (per-language extractors) adds extractor results
                           ─► LexicalEnricher adds prop_lex_* + the lexical_index
                                ─► graph ready for `serve`
```

## Schema source of truth

- **Neo4j shape:** `graph/GraphStore.java` is canonical (it creates the indexes; there are no other DDL sources). Property names like `label_lower` / `fqn_lower` / `prop_*` are decided here.
- **H2 shape:** `cache/AnalysisCache.java`'s `SCHEMA_SQL` constant is canonical. There is no separate migration directory — `CACHE_VERSION` is the migration mechanism.
- **Domain shape:** `model/{CodeNode,CodeEdge,NodeKind,EdgeKind}.java` are canonical. Detectors reference these enums by symbol; never use the lowercase string forms in detector code.

If you change any of the three, **update the other two seams** (or document why you didn't).
