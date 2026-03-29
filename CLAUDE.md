# OSSCodeIQ (Java) -- Project Instructions

## What This Project Is

**OSSCodeIQ** -- a CLI tool + server that scans codebases to build a deterministic code knowledge graph. No AI, no external APIs -- pure static analysis. 106 detectors, 35+ languages, Neo4j Embedded graph database, Hazelcast distributed cache, Spring AI MCP server, REST API, web UI.

- **Maven coordinates:** `io.github.randomcodespace.iq:code-iq`
- **CLI command:** `code-iq` (via `java -jar`)
- **Java package:** `io.github.randomcodespace.iq` (under `src/main/java/`)
- **GitHub repo:** `RandomCodeSpace/code-iq` (branch: `java`)
- **Cache directory on disk:** `.code-intelligence` (SQLite analysis cache)
- **Config file:** `.osscodeiq.yml` (project-level overrides)

## Tech Stack

- Java 25 (virtual threads, pattern matching, records, sealed classes)
- Spring Boot 4.0.5
- Neo4j Embedded 2026.02.3 (Community Edition, no external server)
- Hazelcast 5.6.0 (distributed cache, K8s auto-discovery)
- Spring AI 1.1.4 (MCP server, streamable HTTP)
- JavaParser 3.28.0 (Java AST analysis)
- ANTLR 4.13.2 (TypeScript/JavaScript, Python, Go, C#, Rust, C++ grammars)
- Picocli 4.7.7 (CLI framework, integrated with Spring Boot)
- Thymeleaf + HTMX (web UI)
- SQLite JDBC (incremental analysis cache)

## Architecture

```
FileDiscovery --> Parsers --> Detectors (virtual threads) --> GraphBuilder (buffered) --> Linkers --> LayerClassifier --> Neo4j Embedded
                                                                                                                          |
                                                                                                                    GraphStore (facade)
                                                                                                                    /         |         \
                                                                                                              REST API    MCP Server    Web UI
                                                                                                             (/api)       (/mcp)        (/)
```

### Pipeline Components
- **FileDiscovery** -- discovers files via `git ls-files` or directory walk, maps extensions to languages
- **StructuredParser** -- routes files to JavaParser (Java), ANTLR (TS/Py/Go/C#/Rust/C++), or raw text
- **Detectors** -- 97 concrete detector beans (Spring `@Component`), auto-discovered via classpath scan
- **GraphBuilder** -- buffers all nodes and edges, flushes nodes first then edges (determinism guarantee)
- **Linkers** -- run after all detectors: `TopicLinker`, `EntityLinker`, `ModuleContainmentLinker`
- **LayerClassifier** -- sets `layer` property on every node: `frontend | backend | infra | shared | unknown`
- **GraphStore** -- facade over Neo4j, delegates Cypher operations
- **AnalysisCache** -- SQLite-backed file hash cache for incremental analysis

### Spring Profiles
- **`indexing`** -- active during CLI analyze/stats/graph/query/find/flow/bundle/cache/plugins commands. Starts Neo4j Embedded, runs analysis pipeline.
- **`serving`** -- active during `serve` command. Starts REST API, MCP server, web UI, health endpoint.

## Package Structure

```
io.github.randomcodespace.iq
  |-- CodeIqApplication.java       # Spring Boot main class
  |-- analyzer/                    # Pipeline: Analyzer, FileDiscovery, GraphBuilder, LayerClassifier
  |   |-- linker/                  # Cross-file linkers: TopicLinker, EntityLinker, ModuleContainmentLinker
  |-- api/                         # REST controllers: GraphController, FlowController
  |-- cache/                       # AnalysisCache (SQLite), FileHasher
  |-- cli/                         # Picocli commands (12 commands + CodeIqCli parent + CliOutput helper)
  |-- config/                      # Spring config: Neo4jConfig, HazelcastConfig, CodeIqConfig, JacksonConfig
  |-- detector/                    # Detector interface + 97 concrete detectors
  |   |-- auth/                    # LDAP, certificate, session/header auth
  |   |-- config/                  # YAML, JSON, TOML, INI, properties, K8s, Helm, GHA, etc.
  |   |-- cpp/                     # C++ structures
  |   |-- csharp/                  # EF Core, Minimal APIs, C# structures
  |   |-- docs/                    # Markdown structure
  |   |-- frontend/                # React, Vue, Angular, Svelte, frontend routes
  |   |-- generic/                 # Generic imports
  |   |-- go/                      # Go web, ORM, structures
  |   |-- iac/                     # Terraform, Dockerfile, Bicep
  |   |-- java/                    # 27 Java detectors (Spring, JPA, Kafka, gRPC, etc.)
  |   |-- kotlin/                  # Ktor, Kotlin structures
  |   |-- proto/                   # Proto structures
  |   |-- python/                  # Django, FastAPI, Flask, SQLAlchemy, Celery, etc.
  |   |-- rust/                    # Actix-web, Rust structures
  |   |-- scala/                   # Scala structures
  |   |-- shell/                   # Bash, PowerShell
  |   |-- typescript/              # Express, NestJS, Fastify, Prisma, TypeORM, etc.
  |-- flow/                        # FlowEngine, FlowRenderer, FlowViews, FlowModels
  |-- grammar/                     # ANTLR parser factory + generated parsers
  |   |-- cpp/, csharp/, golang/, javascript/, python/, rust/
  |-- graph/                       # GraphStore (facade), GraphRepository (Spring Data Neo4j)
  |-- health/                      # GraphHealthIndicator (Spring Actuator)
  |-- mcp/                         # McpTools (21 Spring AI @Tool methods)
  |-- model/                       # CodeNode, CodeEdge, NodeKind (31), EdgeKind (26)
  |-- query/                       # QueryService (graph queries), StatsService (categorized stats)
  |-- web/                         # ExplorerController (Thymeleaf web UI)
```

## Critical Rules

### Determinism is Non-Negotiable
- Same input MUST produce same output, every time
- No `Set` iteration without sorting first (`TreeSet` or `stream().sorted()`)
- No dependency on thread completion order (GraphBuilder uses indexed result slots)
- All detectors must be stateless -- no mutable instance fields, use method-local state only
- Collections in results must be deterministically ordered

### Cross-Backend Consistency
- The Python version has 3 backends (NetworkX, SQLite, KuzuDB). The Java version uses Neo4j Embedded only.
- Node and edge counts should be consistent across runs (verified by benchmarks: 3 runs, identical counts)

### Virtual Thread Safety
- All file I/O and Neo4j operations run on virtual threads
- The SQLite analysis cache uses `synchronized` blocks for thread safety
- Hazelcast cache operations are thread-safe by design
- Detectors MUST be stateless -- Spring `@Component` beans are singletons

## CLI Commands

| Command | Description |
|---------|-------------|
| `analyze [path]` | Scan codebase and build knowledge graph |
| `stats [path]` | Show rich categorized statistics from analyzed graph |
| `graph [path]` | Export graph (JSON, YAML, Mermaid, DOT) |
| `query [path]` | Query graph relationships (consumers, producers, callers) |
| `find [what] [path]` | Preset queries (endpoints, guards, entities, topics, etc.) |
| `cypher [query]` | Execute raw Cypher queries against Neo4j |
| `flow [path]` | Generate architecture flow diagrams |
| `serve [path]` | Start web UI + REST API + MCP server |
| `bundle [path]` | Package graph + source into distributable ZIP |
| `cache [action]` | Manage analysis cache |
| `plugins [action]` | List and inspect detectors |
| `version` | Show version info |

## Server Endpoints

### REST API (`/api`)
- `GET /api/stats` -- Graph statistics
- `GET /api/stats/detailed?category=` -- Rich categorized stats
- `GET /api/kinds` -- Node kinds with counts
- `GET /api/kinds/{kind}` -- Paginated nodes by kind
- `GET /api/nodes`, `GET /api/edges` -- Paginated queries
- `GET /api/nodes/{id}/detail` -- Full node detail with edges
- `GET /api/nodes/{id}/neighbors` -- Neighbor traversal
- `GET /api/ego/{center}` -- Ego subgraph
- `GET /api/query/cycles`, `/shortest-path`, `/consumers/{id}`, `/producers/{id}`, `/callers/{id}`, `/dependencies/{id}`, `/dependents/{id}`
- `GET /api/triage/component?file=`, `/impact/{id}` -- Agentic triage
- `GET /api/search?q=` -- Free-text search
- `GET /api/file?path=` -- Source files (path traversal protected)
- `GET /api/flow/{view}` -- Flow diagrams
- `POST /api/analyze` -- Trigger analysis

### MCP Tools (21)
`get_stats`, `get_detailed_stats`, `query_nodes`, `query_edges`, `get_node_neighbors`, `get_ego_graph`, `find_cycles`, `find_shortest_path`, `find_consumers`, `find_producers`, `find_callers`, `find_dependencies`, `find_dependents`, `generate_flow`, `analyze_codebase`, `run_cypher`, `find_component_by_file`, `trace_impact`, `find_related_endpoints`, `search_graph`, `read_file`

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
4. For Java files needing AST access, extend `AbstractJavaParserDetector`
5. For multi-language support via ANTLR, extend `AbstractAntlrDetector`
6. For regex-only detection, extend `AbstractRegexDetector`
7. Create test in `src/test/java/.../detector/<category>/MyDetectorTest.java`
8. Include a determinism test (run twice, assert identical output)
9. Run `mvn test` -- all tests must pass

### Detector Base Classes
| Class | Use Case |
|-------|----------|
| `Detector` | Interface -- implement directly for simple detectors |
| `AbstractRegexDetector` | Regex-based pattern matching (most detectors) |
| `AbstractJavaParserDetector` | Java AST via JavaParser (Spring, JPA, etc.) |
| `AbstractAntlrDetector` | ANTLR grammar-based (TS, Python, Go, C#, Rust, C++) |
| `AbstractStructuredDetector` | Structured file parsing (YAML, JSON, TOML, etc.) |

## Testing

```bash
# Run all tests
mvn test

# Run a specific test class
mvn test -Dtest=SpringRestDetectorTest

# Run with verbose output
mvn test -Dsurefire.useFile=false
```

- Every detector needs: positive match test, negative match test, determinism test
- Server tests use Spring Boot `@SpringBootTest` with `@AutoConfigureMockMvc`
- MCP tools tested by calling `McpTools` methods directly
- 134 test files in `src/test/java/`

## Build Commands

```bash
# Build (skip tests)
mvn clean package -DskipTests

# Build + test
mvn clean package

# Run
java -jar target/code-iq-*.jar analyze /path/to/repo

# Docker
docker build -t code-iq .
docker run -v /path/to/repo:/data code-iq analyze /data

# SpotBugs static analysis
mvn spotbugs:check

# OWASP dependency vulnerability check
mvn dependency-check:check

# Checkstyle
mvn checkstyle:check
```

## Key Files

| File | Purpose |
|------|---------|
| `CodeIqApplication.java` | Spring Boot main class |
| `analyzer/Analyzer.java` | Pipeline orchestrator (discovery -> detect -> build -> link -> classify) |
| `analyzer/FileDiscovery.java` | File discovery via git ls-files or directory walk |
| `analyzer/GraphBuilder.java` | Buffered graph construction (nodes first, then edges) |
| `analyzer/LayerClassifier.java` | Deterministic layer classification |
| `analyzer/linker/TopicLinker.java` | Links producers/consumers to topics |
| `analyzer/linker/EntityLinker.java` | Links entities to repositories |
| `analyzer/linker/ModuleContainmentLinker.java` | Links modules to contained nodes |
| `detector/Detector.java` | Detector interface |
| `detector/AbstractRegexDetector.java` | Base class for regex detectors |
| `detector/AbstractJavaParserDetector.java` | Base class for JavaParser-based detectors |
| `detector/AbstractAntlrDetector.java` | Base class for ANTLR-based detectors |
| `model/NodeKind.java` | 31 node types enum |
| `model/EdgeKind.java` | 26 edge types enum |
| `model/CodeNode.java` | Graph node entity (Spring Data Neo4j) |
| `model/CodeEdge.java` | Graph edge entity (Spring Data Neo4j) |
| `graph/GraphStore.java` | Neo4j facade |
| `graph/GraphRepository.java` | Spring Data Neo4j repository |
| `config/Neo4jConfig.java` | Embedded Neo4j configuration |
| `config/HazelcastConfig.java` | Hazelcast cache configuration |
| `config/CodeIqConfig.java` | Application configuration properties |
| `config/ProjectConfigLoader.java` | Loads .osscodeiq.yml overrides |
| `cache/AnalysisCache.java` | SQLite incremental cache |
| `api/GraphController.java` | REST API endpoints |
| `api/FlowController.java` | Flow diagram endpoints |
| `mcp/McpTools.java` | 21 MCP tool definitions (Spring AI @Tool) |
| `query/QueryService.java` | Graph query operations |
| `query/StatsService.java` | Rich categorized statistics |
| `web/ExplorerController.java` | Thymeleaf web UI |
| `health/GraphHealthIndicator.java` | Spring Actuator health check |
| `flow/FlowEngine.java` | Flow diagram generation and rendering |
| `cli/CodeIqCli.java` | Picocli parent command |

## Code Conventions

- Java 25+ features: records, sealed classes, pattern matching, virtual threads
- Spring Boot 4 conventions: constructor injection, `@Component` beans, profile activation
- Picocli for CLI with Spring integration (`picocli-spring-boot-starter`)
- Detectors are `@Component` beans -- stateless, thread-safe, auto-discovered
- ID format: `"{prefix}:{filepath}:{type}:{identifier}"` for cross-file uniqueness
- Properties map for detector-specific metadata (`auth_type`, `framework`, `roles`, etc.)
- `layer` property on every node: `frontend | backend | infra | shared | unknown`
- Neo4j node labels: `CodeNode`, `CodeEdge` (Spring Data Neo4j entities)
- Jackson for JSON serialization, SnakeYAML for YAML
- UTF-8 encoding everywhere (explicit `StandardCharsets.UTF_8`)

## Configuration

### Application properties (`application.properties` / `application.yml`)
- `codeiq.root-path` -- codebase root (default: `.`)
- `codeiq.cache-dir` -- cache directory name (default: `.code-intelligence`)
- `codeiq.max-radius` -- max ego graph radius (default: 10)
- `codeiq.max-depth` -- max impact trace depth (default: 10)

### Project-level overrides (`.osscodeiq.yml`)
Placed in the codebase root, loaded by `ProjectConfigLoader` before analysis.

## Gotchas & Lessons Learned

- **Package name = repo name here**: Unlike the Python version where package is `osscodeiq` but repo is `code-iq`, the Java artifact ID is also `code-iq`.
- **Spring Boot startup overhead**: 8-16s for embedded Neo4j + Spring context init. Use `spring.main.banner-mode=off` for CLI commands.
- **Neo4j deprecation warnings**: `CodeEdge` uses Long IDs (deprecated). Plan to migrate to external IDs.
- **MCP warnings in CLI mode**: "No tool/resource/prompt/complete methods found" -- expected when not in `serving` profile.
- **XML DOCTYPE warnings**: Non-fatal stderr from XML parser encountering DOCTYPE declarations.
- **Virtual thread pinning**: SQLite JDBC operations can pin carrier threads. Use `synchronized` blocks (not `ReentrantLock`) for virtual thread compatibility.
- **ANTLR generated sources**: Generated during `mvn generate-sources` from `.g4` files. Do not edit generated code in `grammar/` subdirectories.
- **Graph builder determinism**: Uses indexed result slots (not append order) to ensure virtual thread completion order does not affect output.

## Updating This File

After significant changes (new detectors, new endpoints, architectural decisions, conventions learned), update this CLAUDE.md to reflect the current state. Keep it concise and actionable.
