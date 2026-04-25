# codeiq (Java) -- Project Instructions

## What This Project Is

**codeiq** -- a CLI tool + server that scans codebases to build a deterministic code knowledge graph. No AI, no external APIs -- pure static analysis. 97 detectors, 35+ languages, Neo4j Embedded graph database, Spring AI MCP server, REST API, web UI.

- **Maven coordinates:** `io.github.randomcodespace.iq:code-iq` (artifactId intentionally unchanged)
- **CLI command:** `codeiq` (via `java -jar`; JAR filename remains `code-iq-*-cli.jar`)
- **Java package:** `io.github.randomcodespace.iq` (under `src/main/java/`)
- **GitHub repo:** `RandomCodeSpace/codeiq` (branch: `main`)
- **Cache directory on disk:** `.codeiq/cache` (H2 analysis cache)
- **Neo4j directory on disk:** `.codeiq/graph/graph.db` (enriched graph)
- **Config file:** `codeiq.yml` (project-level overrides)

## Tech Stack

- Java 25 (virtual threads, pattern matching, records, sealed classes)
- Spring Boot 4.0.5
- Neo4j Embedded 2026.02.3 (Community Edition, no external server)
- Spring AI 2.0.0-M3 (MCP server, `@McpTool` annotations, streamable HTTP)
- JavaParser 3.28.0 (Java AST analysis)
- ANTLR 4.13.2 (TypeScript/JavaScript, Python, Go, C#, Rust, C++ grammars)
- Picocli 4.7.7 (CLI framework, integrated with Spring Boot)
- React 18 + TypeScript + Vite 6 + Ant Design v5 + ECharts v5 (web UI)
- H2 (incremental analysis cache)

## Architecture

### Deployment Model

```
Developer machine:
  codeiq index  /repo  →  H2 cache (.codeiq/cache/)
  codeiq enrich /repo  →  Neo4j (.codeiq/graph/graph.db)
  codeiq bundle /repo  →  bundle.zip (graph + source snapshot)

Remote server (or local):
  codeiq serve /repo   →  read-only API + MCP + UI (from Neo4j)
```

**Key principle:** MCP and API are strictly **read-only**. No data manipulation from the serving layer. Analysis happens only via CLI (`index`/`enrich`). The remote server may not have source code access (bundle deployment model).

### Pipeline

```
index:   FileDiscovery → Parsers → Detectors (virtual threads) → GraphBuilder → H2 cache
enrich:  H2 → Linkers → LayerClassifier → LexicalEnricher → LanguageEnricher → ServiceDetector → Neo4j (UNWIND bulk-load)
serve:   Neo4j → GraphStore → QueryService → REST API / MCP / Web UI
```

### Pipeline Components
- **FileDiscovery** -- discovers files via `git ls-files` or directory walk, maps extensions to languages
- **StructuredParser** -- routes files to JavaParser (Java), ANTLR (TS/Py/Go/C#/Rust/C++), or raw text
- **Detectors** -- 97 concrete detector beans (Spring `@Component`), auto-discovered via classpath scan
- **GraphBuilder** -- buffers all nodes and edges, flushes nodes first then edges (determinism guarantee)
- **Linkers** -- run after all detectors: `TopicLinker`, `EntityLinker`, `ModuleContainmentLinker`
- **LayerClassifier** -- sets `layer` property on every node using node kind, framework, and path heuristics
- **ServiceDetector** -- scans filesystem for build files (30+ build systems), creates SERVICE nodes with CONTAINS edges
- **GraphStore** -- facade over Neo4j, UNWIND-based bulk save, Cypher reads (no SDN for reads)
- **AnalysisCache** -- H2-backed file hash cache for incremental analysis

### Spring Profiles
- **`indexing`** -- active during CLI index/analyze/stats/graph/query/find/flow/bundle/cache/plugins commands. No Neo4j.
- **`serving`** -- active during `serve` command. Starts Neo4j Embedded, REST API, MCP server, web UI.

## Package Structure

```
io.github.randomcodespace.iq
  |-- CodeIqApplication.java       # Spring Boot main class
  |-- analyzer/                    # Pipeline: Analyzer, FileDiscovery, GraphBuilder, LayerClassifier, ServiceDetector
  |   |-- linker/                  # Cross-file linkers: TopicLinker, EntityLinker, ModuleContainmentLinker
  |-- api/                         # REST controllers: GraphController (read-only), FlowController, TopologyController
  |-- cache/                       # AnalysisCache (H2), FileHasher
  |-- cli/                         # Picocli commands: index, enrich, serve, analyze, stats, etc.
  |-- config/                      # Spring config: Neo4jConfig, CodeIqConfig, JacksonConfig
  |-- detector/                    # Detector interface + 97 concrete detectors
  |   |-- auth/                    # LDAP, certificate, session/header auth (cross-cutting)
  |   |-- csharp/                  # EF Core, Minimal APIs, C# structures
  |   |-- frontend/                # React, Vue, Angular, Svelte, frontend routes
  |   |-- generic/                 # Generic imports
  |   |-- go/                      # Go web, ORM, structures
  |   |-- iac/                     # Terraform, Dockerfile, Bicep
  |   |-- jvm/                     # JVM-family languages
  |   |   |-- java/                # 27 Java detectors (Spring, JPA, Kafka, gRPC, etc.)
  |   |   |-- kotlin/              # Ktor, Kotlin structures
  |   |   |-- scala/               # Scala structures
  |   |-- markup/                  # Markdown structure (renamed from docs/)
  |   |-- proto/                   # Proto structures
  |   |-- python/                  # Django, FastAPI, Flask, SQLAlchemy, Celery, etc.
  |   |-- script/                  # Scripting languages
  |   |   |-- shell/               # Bash, PowerShell
  |   |-- sql/                     # (placeholder — follow-up #48)
  |   |-- structured/              # YAML, JSON, TOML, INI, properties, K8s, Helm, GHA, etc. (renamed from config/)
  |   |-- systems/                 # Systems languages
  |   |   |-- cpp/                 # C++ structures
  |   |   |-- rust/                # Actix-web, Rust structures
  |   |-- typescript/              # Express, NestJS, Fastify, Prisma, TypeORM, etc.
  |-- flow/                        # FlowEngine, FlowRenderer, FlowViews, FlowModels
  |-- grammar/                     # ANTLR parser factory + generated parsers
  |-- graph/                       # GraphStore (Neo4j facade), GraphRepository (SDN, writes only)
  |-- health/                      # GraphHealthIndicator (Spring Actuator)
  |-- mcp/                         # McpTools (34 @McpTool methods, read-only, includes intelligence tools)
  |-- model/                       # CodeNode, CodeEdge, NodeKind (32), EdgeKind (27)
  |-- intelligence/               # Intelligence enrichment (Phase 2-5)
  |   |-- lexical/                # LexicalEnricher, LexicalQueryService, DocCommentExtractor, SnippetStore
  |   |-- extractor/              # LanguageEnricher, LanguageExtractor, LanguageExtractionResult
  |   |   |-- java/               # JavaLanguageExtractor
  |   |   |-- typescript/         # TypeScriptLanguageExtractor
  |   |   |-- python/             # PythonLanguageExtractor
  |   |   |-- go/                 # GoLanguageExtractor
  |   |-- evidence/               # EvidencePack, EvidencePackAssembler
  |   |-- query/                  # QueryPlanner, QueryRoute, QueryPlan
  |-- query/                       # QueryService, StatsService (categorized), TopologyService
  |-- web/                         # Static resource serving (React SPA)
```

## Critical Rules

### Read-Only Serving Layer
- MCP and API are **strictly read-only** -- no data manipulation
- Analysis/enrichment happens only via CLI (`index`, `enrich`)
- Remote servers may not have source code access (bundle deployment)
- No `POST /api/analyze` or `analyze_codebase` MCP tool

### Determinism is Non-Negotiable
- Same input MUST produce same output, every time
- No `Set` iteration without sorting first (`TreeSet` or `stream().sorted()`)
- No dependency on thread completion order (GraphBuilder uses indexed result slots)
- All detectors must be stateless -- no mutable instance fields, use method-local state only

### Generic Detection -- Not Example-Specific
- Every feature must work for ALL languages and architectures, not just the example given
- Framework detectors must have discriminator guards (e.g., Quarkus detector requires `io.quarkus` import)
- ServiceDetector supports 30+ build systems across all ecosystems, not just Maven
- Never fix for one language and forget others

### Virtual Thread Safety
- All file I/O and Neo4j operations run on virtual threads
- The H2 analysis cache uses `synchronized` blocks for thread safety
- Detectors MUST be stateless -- Spring `@Component` beans are singletons

## CLI Commands

| Command | Description |
|---------|-------------|
| `index [path]` | Memory-efficient batched scanning to H2 (preferred for large codebases) |
| `enrich [path]` | Load H2 into Neo4j, run linkers, classify layers, detect services |
| `serve [path]` | Start read-only web UI + REST API + MCP server (requires enrich first) |
| `analyze [path]` | Legacy in-memory scan (use index+enrich for large codebases) |
| `stats [path]` | Show rich categorized statistics from analyzed graph |
| `graph [path]` | Export graph (JSON, YAML, Mermaid, DOT) |
| `query [path]` | Query graph relationships (consumers, producers, callers) |
| `find [what] [path]` | Preset queries (endpoints, guards, entities, topics, etc.) |
| `cypher [query]` | Execute raw Cypher queries against Neo4j |
| `flow [path]` | Generate architecture flow diagrams |
| `bundle [path]` | Package graph + source into distributable ZIP |
| `cache [action]` | Manage analysis cache |
| `plugins [action]` | List and inspect detectors |
| `topology [path]` | Show service topology map |
| `version` | Show version info |

### Standard Pipeline

```bash
# For large codebases (44K+ files):
codeiq index /path/to/repo           # ~220s for 44K files, writes to H2
codeiq enrich /path/to/repo          # loads H2 → Neo4j with linkers/layers/services
codeiq serve /path/to/repo           # read-only server

# For small codebases:
codeiq analyze /path/to/repo         # in-memory, all-in-one
codeiq serve /path/to/repo           # needs enrich if using index
```

## Server Endpoints (all read-only)

### REST API (`/api`) -- 37 endpoints

**GraphController** (`/api`):
- `GET /api/stats` -- Rich categorized statistics (graph, languages, frameworks, infra, connections, auth, architecture)
- `GET /api/stats/detailed?category=` -- Single category stats
- `GET /api/kinds` -- Node kinds with counts
- `GET /api/kinds/{kind}` -- Paginated nodes by kind
- `GET /api/nodes` -- Paginated node queries
- `GET /api/nodes/{id}/detail` -- Full node detail with edges
- `GET /api/nodes/{id}/neighbors` -- Neighbor traversal
- `GET /api/edges` -- Paginated edge queries
- `GET /api/ego/{center}` -- Ego subgraph
- `GET /api/query/cycles` -- Cycle detection
- `GET /api/query/shortest-path` -- Shortest path between nodes
- `GET /api/query/consumers/{id}`, `/producers/{id}`, `/callers/{id}`, `/dependencies/{id}`, `/dependents/{id}`
- `GET /api/query/dead-code` -- Dead code detection (semantic edge filtering, excludes entry points)
- `GET /api/triage/component?file=` -- Agentic triage by file
- `GET /api/triage/impact/{id}` -- Impact trace
- `GET /api/search?q=` -- Free-text search
- `GET /api/file?path=` -- Source files (path traversal protected)

**TopologyController** (`/api/topology`):
- `GET /api/topology` -- Service topology map
- `GET /api/topology/services/{name}` -- Service detail
- `GET /api/topology/services/{name}/deps` -- Service dependencies
- `GET /api/topology/services/{name}/dependents` -- Service dependents
- `GET /api/topology/blast-radius/{nodeId}` -- Blast radius analysis
- `GET /api/topology/path` -- Find path between services
- `GET /api/topology/bottlenecks` -- Find bottleneck services
- `GET /api/topology/circular` -- Circular dependency detection
- `GET /api/topology/dead` -- Dead service detection

**FlowController** (`/api/flow`):
- `GET /api/flow` -- List available flow views
- `GET /api/flow/{view}` -- Flow diagram for specific view
- `GET /api/flow/{view}/{nodeId}/children` -- Node children in flow
- `GET /api/flow/{view}/{nodeId}/parent` -- Node parent in flow

**IntelligenceController** (`/api/intelligence`):
- `GET /api/intelligence/evidence` -- Evidence pack for a node
- `GET /api/intelligence/manifest` -- Artifact manifest
- `GET /api/intelligence/capabilities` -- Capability matrix

### MCP Tools (34, via `@McpTool` annotation)
`get_stats`, `get_detailed_stats`, `query_nodes`, `query_edges`, `get_node_neighbors`, `get_ego_graph`, `find_cycles`, `find_shortest_path`, `find_consumers`, `find_producers`, `find_callers`, `find_dependencies`, `find_dependents`, `find_dead_code`, `generate_flow`, `run_cypher`, `find_component_by_file`, `trace_impact`, `find_related_endpoints`, `search_graph`, `read_file`, `get_topology`, `service_detail`, `service_dependencies`, `service_dependents`, `blast_radius`, `find_path`, `find_bottlenecks`, `find_circular_deps`, `find_dead_services`, `find_node`, `get_evidence_pack`, `get_artifact_metadata`, `get_capabilities`

## Adding a New Detector

1. Create file in `detector/<category>/MyDetector.java`
2. Implement the `Detector` interface:
   ```java
   @Component
   public class MyDetector implements Detector {
       @Override public String getName() { return "my_detector"; }
       @Override public Set<String> getSupportedLanguages() { return Set.of("java"); }
       @Override public DetectorResult detect(DetectorContext ctx) {
           DetectorResult result = new DetectorResult();
           // Your detection logic here
           return result;
       }
   }
   ```
3. **No registry changes needed** -- auto-discovered via Spring classpath scan
4. **Framework-specific detectors MUST have discriminator guards** -- require framework-specific imports before detecting (e.g., Quarkus requires `io.quarkus`, Fastify requires `fastify` import)
5. For Java files needing AST access, extend `AbstractJavaParserDetector`
6. For multi-language support via ANTLR, extend `AbstractAntlrDetector`
7. For regex-only detection, extend `AbstractRegexDetector`
8. Create test in `src/test/java/.../detector/<category>/MyDetectorTest.java`
9. Include a determinism test (run twice, assert identical output)
10. Run `mvn test` -- all tests must pass

### Detector Base Classes
| Class | Use Case |
|-------|----------|
| `Detector` | Interface -- implement directly for simple detectors |
| `AbstractRegexDetector` | Regex-based pattern matching (most detectors) |
| `AbstractJavaParserDetector` | Java AST via JavaParser (Spring, JPA, etc.) |
| `AbstractAntlrDetector` | ANTLR grammar-based (TS, Python, Go, C#, Rust, C++) |
| `AbstractStructuredDetector` | Structured file parsing (YAML, JSON, TOML, etc.) |
| `AbstractPythonAntlrDetector` | Python ANTLR detectors (shared parse, getBaseClassesText, extractClassBody) |
| `AbstractPythonDbDetector` | Python ORM detectors (adds ensureDbNode/addDbEdge via DetectorDbHelper) |
| `AbstractTypeScriptDetector` | TypeScript regex detectors (shared getSupportedLanguages, detect→detectWithRegex) |
| `AbstractJavaMessagingDetector` | Java messaging detectors (shared CLASS_RE, extractClassName, addMessagingEdge) |

### Shared Detector Helpers
| Class | Purpose |
|-------|---------|
| `DetectorDbHelper` | Static ensureDbNode/addDbEdge for any detector emitting DATABASE_CONNECTION nodes |
| `FrontendDetectorHelper` | Static createComponentNode/lineAt for Angular, React, Vue detectors |
| `StructuresDetectorHelper` | Static addImportEdge/createStructureNode for Scala/Kotlin structures |

## Testing

```bash
# Run all tests (~3219 tests)
mvn test

# Run a specific test class
mvn test -Dtest=SpringRestDetectorTest

# Run E2E quality tests (requires cloned test repo)
E2E_PETCLINIC_DIR=/path/to/spring-petclinic mvn test -Dtest=E2EQualityTest

# Run with verbose output
mvn test -Dsurefire.useFile=false
```

- Every detector needs: positive match test, negative match test, determinism test
- Server tests use standalone MockMvc (no Spring context needed)
- MCP tools tested by calling `McpTools` methods directly
- E2E quality tests validate against Context7-sourced ground truth (21 tests for petclinic)
- Use `@ActiveProfiles("test")` for any `@SpringBootTest` to avoid Neo4j startup

### E2E Quality Testing Strategy (mandatory for detection changes)
1. Build ground truth using Context7 for well-known repos
2. Clone official repo, run full pipeline (index → enrich → serve)
3. Query ALL API endpoints, validate against ground truth
4. Fix findings in loop with parallel agents until 95%+ pass rate
5. Ground truth files: `src/test/resources/e2e/ground-truth-*.json`

## Build Commands

```bash
# Build (skip tests)
mvn clean package -DskipTests

# Build + test
mvn clean package

# Run pipeline
java -jar target/code-iq-*-cli.jar index /path/to/repo
java -jar target/code-iq-*-cli.jar enrich /path/to/repo
java -jar target/code-iq-*-cli.jar serve /path/to/repo

# SpotBugs static analysis
mvn spotbugs:check

# OWASP dependency vulnerability check
mvn dependency-check:check
```

## Key Files

| File | Purpose |
|------|---------|
| `CodeIqApplication.java` | Spring Boot main class |
| `analyzer/Analyzer.java` | Pipeline orchestrator (discovery -> detect -> build -> link -> classify) |
| `analyzer/FileDiscovery.java` | File discovery via git ls-files or directory walk |
| `analyzer/GraphBuilder.java` | Buffered graph construction (nodes first, then edges) |
| `analyzer/LayerClassifier.java` | Deterministic layer classification (kind + framework + path heuristics) |
| `analyzer/ServiceDetector.java` | Service boundary detection from build files (30+ build systems) |
| `analyzer/linker/*.java` | Cross-file linkers: TopicLinker, EntityLinker, ModuleContainmentLinker |
| `detector/Detector.java` | Detector interface |
| `model/NodeKind.java` | 32 node types enum |
| `model/EdgeKind.java` | 27 edge types enum |
| `model/CodeNode.java` | Graph node entity |
| `model/CodeEdge.java` | Graph edge entity |
| `graph/GraphStore.java` | Neo4j facade (UNWIND bulk save, Cypher reads, indexes) |
| `config/Neo4jConfig.java` | Embedded Neo4j configuration |
| `config/CodeIqConfig.java` | Application configuration properties |
| `config/JacksonConfig.java` | Jackson config (FAIL_ON_UNKNOWN_PROPERTIES disabled for MCP compat) |
| `cache/AnalysisCache.java` | H2 incremental cache |
| `api/GraphController.java` | REST API endpoints (read-only) |
| `mcp/McpTools.java` | 34 MCP tool definitions (`@McpTool`, read-only) |
| `query/QueryService.java` | Graph query operations with Spring caching |
| `query/StatsService.java` | Rich categorized statistics (7 categories) |
| `query/TopologyService.java` | Service topology queries |
| `cli/IndexCommand.java` | Memory-efficient batched indexing to H2 |
| `cli/EnrichCommand.java` | H2 → Neo4j with linkers, layers, services |
| `cli/ServeCommand.java` | Read-only server startup |
| `intelligence/extractor/LanguageEnricher.java` | Language-specific enrichment orchestrator (Phase 5) |
| `intelligence/extractor/LanguageExtractor.java` | Language extractor interface |
| `intelligence/evidence/EvidencePackAssembler.java` | Evidence pack generation |
| `intelligence/query/QueryPlanner.java` | Intelligent query routing |
| `intelligence/lexical/LexicalEnricher.java` | Doc comment + snippet enrichment |

## Code Conventions

- Java 25+ features: records, sealed classes, pattern matching, virtual threads
- Spring Boot 4 conventions: constructor injection, `@Component` beans, profile activation
- Spring AI 2.0: `@McpTool`/`@McpToolParam` annotations (not `@Tool`/`@ToolParam`)
- Picocli for CLI with Spring integration (`picocli-spring-boot-starter`)
- Detectors are `@Component` beans -- stateless, thread-safe, auto-discovered
- Framework detectors require discriminator guards (framework-specific imports)
- ID format: `"{prefix}:{filepath}:{type}:{identifier}"` for cross-file uniqueness
- Properties map for detector-specific metadata (`auth_type`, `framework`, `roles`, etc.)
- Spring detectors set `framework: "spring_boot"` on their nodes
- `layer` property on every node: `frontend | backend | infra | shared | unknown`
- Neo4j reads use embedded API (no SDN hydration). Writes use SDN or UNWIND Cypher.
- Neo4j properties round-trip via `prop_*` prefix (written by `bulkSave`, read by `nodeFromNeo4j`)
- Jackson `FAIL_ON_UNKNOWN_PROPERTIES` disabled globally for MCP protocol compatibility
- UTF-8 encoding everywhere (explicit `StandardCharsets.UTF_8`)
- Property key constants: `private static final String PROP_FRAMEWORK = "framework"` — extract when a string literal appears 3+ times in a file

## Configuration

Single source of truth: **`codeiq.yml`** at the repo root. See
`docs/codeiq.yml.example` for the full schema (snake_case throughout;
camelCase accepted as a deprecated alias for one release). Resolution order
(last wins):

1. Built-in defaults (`ConfigDefaults.builtIn()`)
2. `~/.codeiq/config.yml` (user-global)
3. `./codeiq.yml` (project)
4. `CODEIQ_<SECTION>_<KEY>` env vars (e.g. `CODEIQ_SERVING_PORT=9090`)
5. CLI flags on `codeiq <command>`

Validate and introspect with:

```bash
codeiq config validate
codeiq config explain
```

### Spring-owned keys (stay in `application.yml`)

A small set of keys still lives in `src/main/resources/application.yml`
because they drive Spring's `@ConditionalOnProperty` / `@Value` wiring and
have not been migrated into `codeiq.yml`:

- `codeiq.neo4j.enabled` -- profile-conditional toggle (`false` in the
  `indexing` profile, `true` in `serving`).
- `codeiq.neo4j.bolt.port` -- embedded Neo4j Bolt listener port.
- `codeiq.cors.allowed-origin-patterns` -- CORS allow-list for the REST API.
- `codeiq.ui.enabled` -- toggles the React SPA static resource handler.

`UnifiedConfigBeans` bridges the unified config to the legacy `CodeIqConfig`
bean for code paths that haven't been ported yet.

## Gotchas & Lessons Learned

- **Pipeline is index → enrich → serve**: Don't put analysis/enrichment in serve. Serve is read-only.
- **MCP/API is read-only**: No data manipulation from serving layer. Remote servers may lack source code.
- **Framework false positives**: Quarkus/Micronaut/Fastify detectors matched generic patterns (router.get, @Transactional). Always add discriminator guards requiring framework-specific imports.
- **Neo4j property round-trip**: Properties stored as `prop_*` keys in Neo4j. `nodeFromNeo4j()` must restore them. Verify properties survive write→read.
- **Edge persistence**: Edges must be attached to source nodes before `bulkSave()`. MATCH silently returns 0 rows for missing nodes -- pre-validate IDs.
- **ServiceDetector must scan filesystem**: Don't rely on node file paths for build file detection. Many build files (pom.xml) don't produce CodeNodes. Walk the filesystem directly.
- **Generic, not example-specific**: Every feature must work for ALL architectures. Don't fix for the specific example given and forget other ecosystems.
- **Neo4j indexes**: Created by `enrich` on `id`, `kind`, `layer`, `module`, `filePath`. Critical for query performance on large graphs.
- **Default batch size is 500**: Performs better than 1000 for indexing.
- **Spring Boot startup overhead**: 8-16s for embedded Neo4j + Spring context init.
- **Virtual thread pinning**: H2 JDBC operations can pin carrier threads. Use `synchronized` blocks (not `ReentrantLock`).
- **ANTLR generated sources**: Generated during `mvn generate-sources` from `.g4` files. Do not edit.
- **`@ActiveProfiles("test")`**: Required on any `@SpringBootTest` to avoid Neo4j startup conflicts.
- **Dead code detection**: Must filter by semantic edges only (calls, imports, depends_on). Exclude structural edges (contains, defines) and entry points (endpoints, config files).
- **H2 reserved words**: `key`, `value`, `order` are reserved in H2 SQL. Use `meta_key`, `meta_value` etc. in CREATE TABLE statements.
- **Cache versioning**: `AnalysisCache` has a `CACHE_VERSION` constant (currently 2). Bump it when changing hash algorithms or schema to auto-clear stale caches.
- **FileHasher uses SHA-256**: Changed from MD5. Hash output is 64 hex chars (not 32). Tests must expect 64-char hashes.
- **SnakeYAML parses `on` as Boolean.TRUE**: In YAML files, bare `on` key becomes `Boolean.TRUE`. Use `String.valueOf(key)` comparisons, not `Boolean.TRUE.equals(key)` (SonarCloud S2159).
- **Regex possessive quantifiers**: Use `*+` instead of `*` for nested quantifiers like `([^"\\]*(?:\\.[^"\\]*)*)` → `([^"\\]*+(?:\\.[^"\\]*+)*+)` to prevent stack overflow (SonarCloud S5998).
- **Parallel agent conflicts**: Don't dispatch multiple agents editing the same files concurrently. Use worktree isolation or sequential execution.
- **SonarCloud project key**: `RandomCodeSpace_codeiq`, org: `randomcodespace`
- **CI workflow**: Single `ci-java.yml` runs build + SonarCloud analysis. No cross-platform builds needed (JVM).

## Deploy

codeiq's deploy surface is **Maven Central + GitHub Releases** (per RAN-46 AC #10 ruling, option a). The single Java JAR (with the React UI bundled inside) is published via two `workflow_dispatch`-only workflows: `.github/workflows/beta-java.yml` (manual beta cut → Sonatype Central beta + GitHub pre-release) and `.github/workflows/release-java.yml` (manual GA cut with a `version` input → the workflow builds a GPG-signed release commit on a detached HEAD, deploys from that exact tree, then creates and pushes a GPG-signed annotated `vX.Y.Z` tag pointing at the release commit + a GitHub Release). There is no static-CDN frontend, no hosted backend, no VPS — codeiq runs on the developer's machine. See [`shared/runbooks/release.md`](shared/runbooks/release.md) and [`shared/runbooks/engineering-standards.md`](shared/runbooks/engineering-standards.md) §7.1.

## Updating This File

After significant changes (new detectors, new endpoints, architectural decisions, conventions learned), update this CLAUDE.md to reflect the current state. Keep it concise and actionable.
