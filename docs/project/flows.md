# Key Flows

Four flows worth tracing — they cover the main code paths an agent will need to modify or debug. Each lists the file:line entry and the chain of calls. **Line numbers are accurate at the time of writing (2026-04-27)** but rot — `grep` for the symbol if a line drifts.

---

## Flow: `codeiq index <path>` — file scan → H2 cache

**Trigger:** `java -jar code-iq-*-cli.jar index /path/to/repo` from a shell.

**Path through code:**

1. `CodeIqApplication.java` `main(...)` — Spring Boot starts. The first arg (`index`) is *not* `serve`, so the app sets profile `indexing` and `WebApplicationType.NONE` (the `if (isServe) ... else ...` block). No web server spins up.
2. `CodeIqApplication.run(args)` — Picocli takes over: `new CommandLine(codeIqCli, factory).execute(args)`.
3. `cli/CodeIqCli.java` — top-level Picocli `@Command`. Subcommand dispatch routes to `cli/IndexCommand.java`.
4. `cli/IndexCommand.call()` — opens `cache/AnalysisCache` (creates the H2 file at `.codeiq/cache/` if missing; checks `CACHE_VERSION`).
5. `analyzer/FileDiscovery.discover(rootPath)` — runs `git ls-files` if the path is a git repo, else walks the filesystem. Returns a list of `DiscoveredFile`s with language tagged via `analyzer/FileClassifier.java`.
6. For each file, in batches (default 500): hash via `cache/FileHasher.hash(...)` (SHA-256), check the cache.
   - **Cache hit** → reuse existing nodes/edges from H2.
   - **Cache miss** → continue.
7. `analyzer/StructuredParser.parse(file)` — routes to JavaParser (Java), `grammar/AntlrParserFactory` (TS/Py/Go/C#/Rust/C++), or raw text.
8. **Detector fan-out** on virtual threads: every `@Component`-annotated `Detector` whose `getSupportedLanguages()` matches gets called with a `DetectorContext`. Results are collected per file. (Auto-discovery via Spring classpath scan; no manual list.)
9. `analyzer/GraphBuilder.addNodes(...) / addEdges(...)` — buffer to indexed slots so order is independent of thread completion.
10. `cache/AnalysisCache.write(contentHash, nodes, edges, runId)` — persist via UNWIND-friendly batches.
11. CLI prints summary; exit code 0.

**Side effects:** `.codeiq/cache/` H2 file populated/updated. **No Neo4j writes**. No network calls.

**Failure modes:**
- Per-file detector exceptions: caught + logged in `Analyzer.java`'s task wrapper; the file is skipped, the run continues.
- `CACHE_VERSION` mismatch: H2 file is wiped + recreated automatically on startup.
- Disk-full / permission errors: bubble up, run aborts with non-zero exit.

---

## Flow: `codeiq enrich <path>` — H2 → Neo4j with linkers + classifiers

**Trigger:** `java -jar code-iq-*-cli.jar enrich /path/to/repo` (after `index`).

**Path through code:**

1. `CodeIqApplication.main(...)` — same profile-selection logic; `enrich` → `indexing` profile, no web server.
2. `cli/EnrichCommand.call()` — opens `cache/AnalysisCache` (read), opens Neo4j Embedded directly via `DatabaseManagementServiceBuilder` (programmatic — Spring's `@Profile("serving")` Neo4j config is *not* loaded here).
3. `EnrichCommand` reads all nodes + edges from H2 in batches.
4. `graph/GraphStore.bulkSave(nodes, edges)` (line numbers approximate at time of writing — grep the Cypher fragment if drifted):
   - `MATCH (n) WITH n LIMIT 5000 DETACH DELETE n RETURN count(*)` — clear in chunks if a previous graph existed.
   - `CREATE INDEX IF NOT EXISTS` for `id`, `label_lower`, `fqn_lower` + `CREATE FULLTEXT INDEX` for `search_index` and `lexical_index`.
   - `UNWIND $batch AS props CREATE (n:CodeNode) SET n = props` — nodes, batched (default 500).
   - `UNWIND $batch AS e MATCH (a {id: e.src}) MATCH (b {id: e.tgt}) CREATE (a)-[r:EDGE_KIND]->(b)` — edges, batched. **Silently drops rows where source/target IDs miss.**
5. `analyzer/linker/*` — runs in order: `TopicLinker`, `EntityLinker`, `ModuleContainmentLinker`, `GuardLinker`. Each adds cross-file edges (e.g. `PRODUCES`/`CONSUMES` from a topic name appearing in two services).
6. `analyzer/LayerClassifier.classify(...)` — sets `n.layer` on every node based on `kind`, `framework`, and path heuristics.
7. `analyzer/ServiceDetector.detect(rootPath)` — walks the filesystem (not the Neo4j graph) for build files (Maven, Gradle, npm, Cargo, go.mod, etc. — 30+). Creates `:CodeNode {kind: 'service'}` nodes and `CONTAINS` edges to every module/file inside the service boundary.
8. `intelligence/extractor/LanguageEnricher` — runs per-language extractors (`JavaLanguageExtractor`, `TypeScriptLanguageExtractor`, `PythonLanguageExtractor`, `GoLanguageExtractor`) to add language-specific properties.
9. `intelligence/lexical/LexicalEnricher` — extracts doc comments (`DocCommentExtractor`) and persists to `prop_lex_comment`; populates the `lexical_index` fulltext index.
10. CLI prints summary; exit 0.

**Side effects:** `.codeiq/graph/graph.db/` populated. H2 cache untouched.

**Failure modes:**
- Edge with missing source/target ID: silently dropped by Cypher MATCH. Mitigation: pre-validate IDs before passing to `bulkSave`. **Most common cause of "missing relationships" bugs.**
- Property round-trip failure: a domain property survives `bulkSave` but `nodeFromNeo4j()` doesn't know to restore it → silent property loss. Verify by reading back any node you just wrote.

---

## Flow: `codeiq serve <path>` — REST + MCP + UI request lifecycle

**Trigger:** `java -jar code-iq-*-cli.jar serve /path/to/repo` (after `enrich`). Then a browser hits `http://localhost:8080/explorer` or an MCP client calls a tool.

**Path through code (cold start):**

1. `CodeIqApplication.main(...)` — first arg is `serve` → profile `serving` activated; web server starts.
2. Spring loads beans gated by `@Profile("serving")`: all 4 controllers in `api/`, `mcp/McpTools` (via Spring AI starter), the Neo4j `@Configuration` in `config/Neo4jConfig.java` (only when `codeiq.neo4j.enabled=true`).
3. Neo4j Embedded starts; `health/GraphHealthIndicator` reports status to `/actuator/health`.
4. Spring Boot's static-resource handler binds `src/main/resources/static/` (the bundled SPA) to `/`.
5. Server bound — `http://localhost:8080` ready.

**Path through code (REST request, e.g. `GET /api/stats`):**

1. Browser hits `/api/stats`.
2. `api/GraphController.getStats(...)` (`@GetMapping("/stats")`) is dispatched (carries `@Profile("serving")`).
3. Controller delegates to `query/StatsService.getStats()`.
4. `StatsService` runs Cypher queries via `graph/GraphStore.queryNodes(...)` (raw Cypher, not SDN).
5. Results aggregated into a `Map<String, Object>` and serialized by Jackson.
6. HTTP response returned.

**Path through code (MCP tool call, e.g. `find_dead_code`):**

1. MCP client (Claude Desktop, an LLM agent, the SPA's `McpConsole`) sends a JSON-RPC call to `/mcp` (mounted by Spring AI's `spring-ai-starter-mcp-server-webmvc`).
2. Spring AI dispatches to the matching `@McpTool`-annotated method on `mcp/McpTools.java`.
3. The MCP tool delegates to `query/QueryService.findDeadCode()` (or similar).
4. `QueryService` runs Cypher (filters by semantic edges only — `calls`, `imports`, `depends_on`; excludes structural `contains`, `defines`, and entry points like endpoints / config files — see [`CLAUDE.md`](../../CLAUDE.md) "Gotchas").
5. Result returned as JSON-RPC response.

**Side effects:** None — strictly read-only.

**Failure modes:**
- Calling `serve` before `enrich` → `health/GraphHealthIndicator` reports DOWN; queries return empty results. Fix: run `enrich` first.
- CORS rejection if the SPA is being served from a different origin in dev: configure `codeiq.cors.allowed-origin-patterns` in `application.yml` (or env: `CODEIQ_CORS_ALLOWED_ORIGIN_PATTERNS`).
- `FAIL_ON_UNKNOWN_PROPERTIES` is globally disabled (`config/JacksonConfig.java`) — MCP protocol clients won't break on field additions, but it also hides typos in JSON inputs. Validate at the controller boundary.

---

## Flow: Adding a new detector and seeing it run

**Trigger:** developer adds `MyDetector.java` and rebuilds.

**Path through code (compile-time + first run):**

1. `src/main/java/io/github/randomcodespace/iq/detector/<category>/MyDetector.java` — new file, `@Component`-annotated, `@DetectorInfo(...)`-annotated, extending one of the `Abstract*Detector` base classes.
2. `mvn package` — compiles the class.
3. On the next `codeiq index <path>`:
   - Spring Boot starts under `indexing` profile, classpath-scans `io.github.randomcodespace.iq` for `@Component`s.
   - `MyDetector` is instantiated as a singleton bean.
   - `analyzer/Analyzer` (or `cli/IndexCommand`) iterates Spring's `Map<String, Detector>` of all bean instances.
4. For every file whose language matches `getSupportedLanguages()`, `MyDetector.detect(ctx)` is called on a virtual thread.
5. Returned `DetectorResult` is folded into `GraphBuilder` (nodes-first, then edges).
6. From there: identical to the `index` flow — H2 cache write, then `enrich`, then visible via `serve`.

**Verification:**
- `codeiq plugins list` introspects via `@DetectorInfo` and confirms the detector is live.
- `codeiq stats <path>` — node-kind counts should change after re-indexing.
- Unit test `MyDetectorTest` (positive + negative + determinism) must pass via `mvn test`.

**Failure modes:**
- Forgot `@Component` → silently disabled, no error. Test won't catch it (unit tests instantiate directly). Catch via `codeiq plugins list` showing the detector is missing.
- Missing discriminator guard on a framework detector → false positives across other frameworks. Catch via the negative-match unit test.
- Stateful instance fields → race conditions across virtual threads. Catch via the determinism test.
