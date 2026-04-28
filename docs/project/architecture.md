# Architecture

## High-level shape

codeiq is a **two-mode Spring Boot application** that ships as one JAR with the React SPA bundled inside:

- **Indexing mode** (`index`, `enrich`, and most other CLI commands): Spring profile `indexing`, no web server, virtual-thread-driven file scanning + detector pipeline writing to H2 (cache) then Neo4j Embedded (graph).
- **Serving mode** (`serve` only): Spring profile `serving`, web server up, REST API + Spring AI MCP server + React SPA reading from the already-populated Neo4j directory. Strictly read-only — no detector code runs in this profile.

```
                        ┌──────────────────────────┐
   filesystem  ───►     │  index  (FileDiscovery + │
   (any repo)           │   Detectors + GraphBuilder)│  ──►  H2 cache (.codeiq/cache/)
                        └──────────────────────────┘
                                                              │
                        ┌──────────────────────────┐          │
                        │  enrich  (Linkers +      │  ◄───────┘
                        │   LayerClassifier +      │
                        │   ServiceDetector +      │
                        │   LanguageEnricher +     │
                        │   LexicalEnricher)       │  ──►  Neo4j (.codeiq/graph/graph.db)
                        └──────────────────────────┘                  │
                                                                       │
   developer / agent  ◄──  REST + MCP + React SPA  ◄────  serve ◄─────┘
   (read-only)              (Spring profile = serving)
```

Profile selection happens in `CodeIqApplication.java`'s `main` (around the `boolean isServe = "serve".equalsIgnoreCase(command)` block): the first CLI arg is matched against `serve` → `serving`; everything else → `indexing`. `indexing` sets `WebApplicationType.NONE`.

## Components

### Pipeline orchestrator (`analyzer/`)
- **Lives in:** `src/main/java/io/github/randomcodespace/iq/analyzer/`
- **Responsibility:** Discover files, route to parsers, fan out to detectors on virtual threads, fold results into a single graph buffer, then run cross-file linkers and the layer classifier.
- **Key files:**
  - `Analyzer.java` — top-level pipeline (in-memory mode for `analyze` command).
  - `FileDiscovery.java` — `git ls-files` first, falls back to directory walk; maps extensions → languages via `FileClassifier.java`.
  - `StructuredParser.java` — routes Java to JavaParser, ANTLR-supported langs to `grammar/AntlrParserFactory.java`, others to raw text.
  - `GraphBuilder.java` — buffered build (nodes-first, then edges) — determinism guarantee.
  - `LayerClassifier.java` — sets `layer ∈ {frontend|backend|infra|shared|unknown}` on every node.
  - `ServiceDetector.java` — filesystem walk for build files (30+ build systems) → SERVICE nodes with `CONTAINS` edges.
  - `linker/` — 4 linkers run after detectors: `EntityLinker`, `GuardLinker`, `ModuleContainmentLinker`, `TopicLinker` (`Linker.java` is the interface; `LinkResult.java` is the return type).
  - `ConfigScanner.java`, `InfrastructureRegistry.java`, `ArchitectureKeywordFilter.java` — supporting passes.
- **Talks to:** `detector/` (fan-out), `cache/AnalysisCache.java` (write), `graph/GraphStore.java` (write — only during `enrich`).
- **Owns:** in-memory graph buffer during a single run.

### Detector layer (`detector/`)
- **Lives in:** `src/main/java/io/github/randomcodespace/iq/detector/`
- **Responsibility:** 99 concrete detectors that turn parsed files into nodes + edges. Auto-discovered as Spring `@Component`s; no registry to maintain.
- **Categories (one subdir each):** `auth/`, `csharp/`, `frontend/`, `generic/`, `go/`, `iac/`, `jvm/{java,kotlin,scala}/`, `markup/`, `proto/`, `python/`, `script/{shell,...}/`, `sql/`, `structured/`, `systems/{cpp,rust}/`, `typescript/`.
- **Base classes:** `Detector` (interface), `AbstractRegexDetector`, `AbstractJavaParserDetector`, `AbstractAntlrDetector`, `AbstractStructuredDetector`, `AbstractPythonAntlrDetector`, `AbstractPythonDbDetector`, `AbstractTypeScriptDetector`, `AbstractJavaMessagingDetector`. Plus three static helpers: `DetectorDbHelper`, `FrontendDetectorHelper`, `StructuresDetectorHelper`. Full table: see [`conventions.md`](conventions.md) §"Detector base classes".
- **Talks to:** parsed AST input (JavaParser CompilationUnit, ANTLR ParseTree, or raw text) via `DetectorContext`. Writes to a thread-local `DetectorResult`.
- **Owns:** nothing — must be stateless. Spring beans are singletons.

### Graph store (`graph/`)
- **Lives in:** `src/main/java/io/github/randomcodespace/iq/graph/`
- **Responsibility:** Facade over Neo4j Embedded — UNWIND-batched bulk save for writes, raw Cypher for reads (no Spring Data Neo4j hydration on the read path for performance).
- **Key files:**
  - `GraphStore.java` — `bulkSave(List<CodeNode>, List<CodeEdge>)`, `queryNodes(...)`, fulltext search via `db.index.fulltext.queryNodes`. Creates 5 indexes on first save (3 b-tree + 2 fulltext — see [`data-model.md`](data-model.md)).
  - `GraphRepository.java` — Spring Data Neo4j repository, used **only on the write path** (legacy).
- **Talks to:** Neo4j Embedded via `org.neo4j.graphdb` API (no Bolt for in-process reads).
- **Owns:** the Neo4j directory at `.codeiq/graph/graph.db/`.

### Analysis cache (`cache/`)
- **Lives in:** `src/main/java/io/github/randomcodespace/iq/cache/`
- **Responsibility:** Per-file content-hash cache so re-running `index` only re-detects changed files.
- **Key files:** `AnalysisCache.java` (H2 schema + read/write API, `ReentrantReadWriteLock`-guarded, `CACHE_VERSION = 4`), `FileHasher.java` (SHA-256, 64-hex output).

### REST API (`api/`)
- **Lives in:** `src/main/java/io/github/randomcodespace/iq/api/`
- **Files:** `GraphController.java` (`/api/**`), `FlowController.java` (`/api/flow/**`), `TopologyController.java` (`/api/topology/**`), `IntelligenceController.java` (`/api/intelligence/**`), `SafeFileReader.java` (helper, path-traversal guard).
- All controllers carry `@Profile("serving")` — they aren't loaded in indexing mode.
- 37 endpoints, all read-only. Full enumeration in [`CLAUDE.md`](../../CLAUDE.md) §"Server Endpoints".

### MCP server (`mcp/`)
- **File:** `src/main/java/io/github/randomcodespace/iq/mcp/McpTools.java` — 34 `@McpTool`-annotated methods. Spring AI's `spring-ai-starter-mcp-server-webmvc` auto-registers them on a streamable HTTP transport at `/mcp`. Read-only.

### Intelligence enrichment (`intelligence/`)
- **Lives in:** `src/main/java/io/github/randomcodespace/iq/intelligence/`
- **Sub-packages:** `lexical/` (doc-comment + snippet enrichment), `extractor/` (per-language extractors: `java/`, `typescript/`, `python/`, `go/`), `evidence/` (evidence-pack assembly for retrieval), `query/` (`QueryPlanner` for intelligent routing).
- Runs during `enrich` after structural data is in Neo4j; produces `prop_lex_*` properties indexed by the `lexical_index` fulltext index.

### CLI (`cli/`)
- **Lives in:** `src/main/java/io/github/randomcodespace/iq/cli/`
- **Files:** 20 — `CodeIqCli.java` (top-level), 14 commands (`Index`, `Enrich`, `Serve`, `Analyze`, `Stats`, `Graph`, `Query`, `Find`, `Cypher`, `Topology`, `Flow`, `Bundle`, `Cache`, `Plugins`), config subcommands (`ConfigCommand`, `ConfigExplainSubcommand`, `ConfigValidateSubcommand`), `VersionCommand`, helper `CliOutput`.
- All commands are `@Component`s; Picocli + Spring integration via `picocli-spring-boot-starter`.

### React SPA (`src/main/frontend/`)
- See [`ui.md`](ui.md). Vite builds into `src/main/resources/static/` — Spring Boot's static handler serves it from inside the JAR when `codeiq.ui.enabled=true`.

## Layering / dependency rules

The package graph enforces a one-way flow:

```
cli/ ──► analyzer/ ──► detector/ ─► model/
              │              │
              └► linker/      └► grammar/   (ANTLR factory)
              │
              ├► cache/  (H2)
              └► graph/  (Neo4j) ──► api/ ──► query/  (read path)
                                       │
                                       └► mcp/  (same QueryService)
```

- `model/` (CodeNode, CodeEdge, NodeKind, EdgeKind) is the dependency floor — depends on nothing in this codebase.
- `detector/` may import `model/` and `grammar/` — never `analyzer/`, `cli/`, or `api/`.
- `api/` and `mcp/` may import `query/` and `model/` — never `detector/` or `analyzer/` (read-only at serving time).
- `analyzer/` may import everything below it — it's the orchestrator.

The `@Profile("serving")` annotation on every controller and on Neo4j-only beans (see `config/Neo4jConfig.java`) is what enforces "no writes during serving" at runtime; the package layering is convention, not a lint rule.

## Cross-cutting concerns

- **Logging:** SLF4J + Spring Boot's default Logback. `application.yml` quiets noisy `org.springframework.ai.mcp` and `PostProcessorRegistrationDelegate` to WARN.
- **Error handling:** Pipeline errors are logged + counted, never abort a whole run. Detector exceptions are caught per-file (the run continues with a logged warning); see `Analyzer.java` task wrapping. CLI commands return `int` exit codes via Picocli.
- **Auth / authz:** None — codeiq runs on the developer's machine. The serving layer trusts the loopback caller. CORS is configurable via `codeiq.cors.allowed-origin-patterns` (`application.yml` / `CorsConfig.java`).
- **Observability:** Spring Boot Actuator (`/actuator/health` with liveness + readiness probes per `application.yml`); `health/GraphHealthIndicator.java` reports Neo4j status. No metrics export — by design (offline tool).
- **Config:** Hierarchical, last-wins: built-in defaults → `~/.codeiq/config.yml` → `./codeiq.yml` → `CODEIQ_*` env → CLI flags. `UnifiedConfigBeans` bridges the unified config to the legacy `CodeIqConfig` bean. Spring-owned keys (`codeiq.neo4j.enabled`, `codeiq.neo4j.bolt.port`, `codeiq.cors.allowed-origin-patterns`, `codeiq.ui.enabled`) live in `application.yml` because they drive `@ConditionalOnProperty` / `@Value` wiring. Full schema: [`docs/codeiq.yml.example`](../codeiq.yml.example).

## Concurrency model

- Detector fan-out runs on **virtual threads** (`Executors.newVirtualThreadPerTaskExecutor()` in `Analyzer.java`). Java 25 + JEP 491 means `synchronized` and `j.u.c.locks` no longer pin carrier threads, so the cache's `ReentrantReadWriteLock` is purely a logical concurrency primitive — not a workaround.
- Detectors are stateless `@Component` singletons (Spring's default scope). Per-file mutable state lives in method-local `DetectorContext` / `DetectorResult` instances.
- `GraphBuilder` collects results into indexed slots (one per file) so iteration order is independent of thread completion order — this is the determinism guarantee.

## Why it's shaped this way

- **Three-stage pipeline (`index`/`enrich`/`serve`) instead of one all-in-one `analyze`:** large codebases (44 K+ files in the original target) blow heap if scanning + Neo4j ingestion happen in the same JVM run. `index` writes to H2 in batches (default 500), `enrich` reads from H2 and bulk-loads with UNWIND. `analyze` is kept as a legacy in-memory shortcut for small repos. See `CLAUDE.md` §"Pipeline".
- **Embedded Neo4j (not a server):** zero-ops deployment for an offline tool; bundle model means the serving host doesn't even need source code, just the `.codeiq/graph/` directory.
- **Read-only serving layer:** lets the server be deployed to a "remote" environment where source code is forbidden, while analysis still happens on the developer's box. See [`CLAUDE.md`](../../CLAUDE.md) §"Critical Rules / Read-Only Serving Layer".
- **Auto-discovery of detectors via `@Component`:** detectors are added by dropping a class — no registry edits, no plugin manifest. The trade-off is that mistakes (forgetting `@Component`) silently disable a detector; the `plugins` CLI command exists to introspect what's actually live.
