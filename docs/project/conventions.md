# Conventions

Rules to follow when modifying codeiq. Each item is grounded in an existing file. The 7 most important ones are summarized in [`PROJECT_SUMMARY.md`](../../PROJECT_SUMMARY.md) §"Conventions an agent must respect"; this file is the long form.

## Code style

- **Java 25 idioms encouraged** — records, sealed classes, pattern matching, virtual threads. Don't down-port to older idioms; this codebase is on the latest LTS-track.
- **Constructor injection only.** No field injection (`@Autowired` on fields), no setter injection. See any `@Component` / `@Service` in the codebase, e.g. `api/GraphController.java`.
- **Property-key constants** — when a string literal appears 3+ times in a file, extract: `private static final String PROP_FRAMEWORK = "framework";`. Saves typo bugs and makes refactors greppable.
- **Spring AI MCP annotations:** use `@McpTool` and `@McpToolParam` (Spring AI 2.x), not `@Tool`/`@ToolParam` (older form). See `mcp/McpTools.java`.
- **UTF-8 explicit:** `StandardCharsets.UTF_8` everywhere — never rely on platform default. `Analyzer.java` shows the import.

## Error handling

- **Pipeline errors don't abort the run.** Per-file detector exceptions are caught and logged; the file is skipped, the run continues. See task wrapping in `analyzer/Analyzer.java`.
- **CLI commands return `int` exit codes** via Picocli's `Callable<Integer>` pattern. See any `cli/*Command.java` (e.g. `cli/EnrichCommand.java`).
- **No `System.exit()` from non-CLI code.** `CodeIqApplication.main` is the only place that calls `SpringApplication.exit(...)` and `System.exit(...)`.
- **No silent fallbacks.** If a detector can't parse a file, log it; don't return an empty result that looks indistinguishable from "nothing matched".

## Naming

- **Java packages:** `io.github.randomcodespace.iq.<area>` (lowercase, no plurals). Detector subpackages match the language family: `detector/jvm/{java,kotlin,scala}/`, `detector/typescript/`, `detector/python/`, `detector/systems/{cpp,rust}/`.
- **Detector class:** `<Framework>Detector` — `SpringSecurityDetector`, `FastifyDetector`, `GoStructuresDetector`. Always ends in `Detector`.
- **Detector test class:** `<Framework>DetectorTest` — colocated under `src/test/java/` with the same package.
- **CLI commands:** `<Verb>Command` — `IndexCommand`, `EnrichCommand`, `ServeCommand`. Picocli `@Command(name = "<verb>")` annotation gives the user-facing name.
- **Node ID format:** `"{prefix}:{filepath}:{type}:{identifier}"` — e.g. `"node:src/main/java/Foo.java:class:Foo"`. The full file path is part of the key — that's how cross-file uniqueness works.
- **Property keys:** snake_case (`auth_type`, `framework`, `roles`). Stored in Neo4j with a `prop_` prefix (`prop_auth_type`, `prop_framework`).
- **Frontend imports:** `@/...` resolves to `src/main/frontend/src/...` (Vite alias in `vite.config.ts`, mirrored in `tsconfig.json`'s `paths`). Always use the alias, never `../../../`.

## Tests

- **Location:** `src/test/java/<same-package-as-source>/`. ~236 test files total.
- **Layers:**
  - **Unit:** plain JUnit, no Spring context. Most detector tests are unit.
  - **Integration:** `@SpringBootTest` with `@ActiveProfiles("test")` — required to suppress Neo4j auto-startup. Standalone MockMvc for controller tests (no full context).
  - **MCP tools:** test by calling `McpTools` methods directly — no protocol round-trip needed.
  - **E2E quality:** `E2EQualityTest` validates against Context7-sourced ground truth (`src/test/resources/e2e/ground-truth-*.json`). Requires the env var `E2E_PETCLINIC_DIR` (or similar) to point at a cloned reference repo.
- **Run a single test:** `mvn test -Dtest=ClassName#methodName`.
- **Every detector needs:**
  1. Positive match — input that should fire, output asserted.
  2. Negative match — input that *looks similar* but shouldn't fire (especially for framework detectors).
  3. **Determinism test** — run the detector twice on the same input, assert output is byte-identical.

## Logging

- **SLF4J** via Spring Boot's default Logback. Pattern across the codebase: `private static final Logger log = LoggerFactory.getLogger(MyClass.class);`.
- `application.yml` already silences known-noisy loggers (`org.springframework.ai.mcp` → WARN, `PostProcessorRegistrationDelegate` → WARN). Don't add more bare `org.springframework.*` loggers without good cause.
- **No PII concerns** — codeiq scans the user's own code; logs go to the user's terminal.

## Adding a new detector

(Authoritative recipe — slightly expanded from [`CLAUDE.md`](../../CLAUDE.md) §"Adding a New Detector".)

1. **Pick the right base class** (table below) and create `src/main/java/io/github/randomcodespace/iq/detector/<category>/<Framework>Detector.java`.
2. **Annotate with `@Component`** (Spring auto-discovery) **and `@DetectorInfo(name=..., category=..., parser=ParserType.X, languages={...}, nodeKinds={...}, edgeKinds={...}, properties={...})`** (used by the `plugins` CLI command for introspection). Live examples: `detector/jvm/java/SpringSecurityDetector.java`, `detector/go/GoStructuresDetector.java`.
3. **Implement `detect(DetectorContext ctx)`** — return a `DetectorResult` populated with `CodeNode`s and `CodeEdge`s. Detectors are stateless; the `DetectorContext` is your scratch space.
4. **Framework detectors require a discriminator guard** — e.g. Quarkus must require `import io.quarkus.*`, Fastify must require `import 'fastify'`. Otherwise you'll match Spring controllers as Quarkus or Express as Fastify. **No exceptions** — this rule is enforced by review.
5. **Property-key constants** for any string literal repeated 3+ times.
6. **Add tests** in `src/test/java/.../detector/<category>/<Framework>DetectorTest.java`: positive, negative, determinism.
7. **Run `mvn test`** — all 236+ tests must still pass.
8. **No registry edit needed** — Spring classpath scan picks up the `@Component`. The `plugins list` CLI command will introspect via `@DetectorInfo`.

### Detector base classes

| Class | Use when |
|---|---|
| `Detector` (interface) | You need full control; rare |
| `AbstractRegexDetector` | Pattern-only detection (most detectors) |
| `AbstractJavaParserDetector` | Java AST via JavaParser (Spring, JPA, etc.) |
| `AbstractAntlrDetector` | ANTLR grammar-based (TS, Python, Go, C#, Rust, C++) |
| `AbstractStructuredDetector` | Structured config files (YAML, JSON, TOML, INI, properties) |
| `AbstractPythonAntlrDetector` | Python ANTLR detectors (shared parse, getBaseClassesText, extractClassBody) |
| `AbstractPythonDbDetector` | Python ORM detectors (adds ensureDbNode/addDbEdge via DetectorDbHelper) |
| `AbstractTypeScriptDetector` | TS regex detectors (shared getSupportedLanguages, detect→detectWithRegex) |
| `AbstractJavaMessagingDetector` | Java messaging detectors (shared CLASS_RE, extractClassName, addMessagingEdge) |

### Shared static helpers (don't subclass — call them)

| Class | Purpose |
|---|---|
| `DetectorDbHelper` | `ensureDbNode` / `addDbEdge` for any detector emitting `DATABASE_CONNECTION` nodes |
| `FrontendDetectorHelper` | `createComponentNode` / `lineAt` for Angular, React, Vue detectors |
| `StructuresDetectorHelper` | `addImportEdge` / `createStructureNode` for Scala/Kotlin structure detectors |

## Adding a new CLI command

1. Create `src/main/java/io/github/randomcodespace/iq/cli/<Verb>Command.java`.
2. Annotate `@Component` and `@picocli.CommandLine.Command(name="<verb>", description="...")`.
3. Implement `Callable<Integer>` returning the exit code.
4. Wire as a subcommand of `CodeIqCli` in `cli/CodeIqCli.java` (it lists subcommands explicitly).
5. If the command needs a Spring profile other than `indexing` (only `serve` does this), update the `if (isServe) ...` block in `CodeIqApplication.main` — note this is **not** generic, so adding another `serving`-profile command means rethinking that conditional.

## Adding a new REST endpoint

1. Add a `@GetMapping` method (read-only — no `@PostMapping`/`@PutMapping`/`@DeleteMapping`) to the appropriate controller in `src/main/java/io/github/randomcodespace/iq/api/`.
2. Delegate to `query/QueryService.java` (or one of its peers — `StatsService`, `TopologyService`) — controllers stay thin.
3. **Mirror it in `mcp/McpTools.java`** as a new `@McpTool`. The MCP tool description must explain when an LLM should call it; copy the wording style of existing tools.
4. Add a controller test using standalone MockMvc (no `@SpringBootTest`).

## Adding a new MCP tool

1. Add a method on `mcp/McpTools.java` annotated `@McpTool(name="...", description="...")`.
2. Parameters: annotate with `@McpToolParam(description="...")`.
3. Return type: anything Jackson can serialize (typically a `Map<String, Object>` or a record). Jackson's `FAIL_ON_UNKNOWN_PROPERTIES` is globally disabled for MCP-protocol compatibility (`config/JacksonConfig.java`).
4. Test by calling the method directly in a unit test — no protocol round-trip needed.

## Things to avoid (anti-patterns)

- **`Set` iteration without sorting** — kills determinism. Use `TreeSet`, `stream().sorted(...)`, or sort the resulting list.
- **Mutable instance state on detectors** — they're Spring singletons; concurrent calls will collide. Per-call state goes in method-local variables / `DetectorContext`.
- **Coarse `synchronized` on `AnalysisCache`** — the `ReentrantReadWriteLock` is deliberate. Don't "simplify" to `synchronized` blocks; that serializes reads unnecessarily.
- **Direct `Boolean.TRUE.equals(yamlKey)`** — SnakeYAML parses bare `on` as `Boolean.TRUE`. Use `String.valueOf(key)` for YAML key comparisons (SonarCloud S2159).
- **Regex with nested non-possessive quantifiers** — use `*+` instead of `*` for nested patterns. `([^"\\]*+(?:\\.[^"\\]*+)*+)` not `([^"\\]*(?:\\.[^"\\]*)*)`. Stack-overflow risk (SonarCloud S5998).
- **Adding a new property to `CodeNode` without round-trip-testing** — Neo4j stores properties as `prop_<key>`; `nodeFromNeo4j()` must restore them. A new property that survives `bulkSave` but not `nodeFromNeo4j` will silently disappear when read back.
- **Edges referencing nodes that don't exist yet** — `bulkSave`'s edge UNWIND silently drops rows whose source/target IDs don't match any node. Pre-validate IDs.
- **Generic patterns in framework detectors** — `router.get(...)` matches Express, Fastify, NestJS, Vue Router, Hono, and probably ten others. Always require a framework-specific import.

## Don't refactor (intentional non-standard choices)

- **Single-file `NodeKind` and `EdgeKind` enums.** They're long (34/28 values) and could be split, but they're load-bearing for cross-file uniqueness and detector readability. Don't split — keeps the type surface in one diff-friendly file. See `model/NodeKind.java`, `model/EdgeKind.java`.
- **No SDN hydration on the read path.** `graph/GraphStore.java` uses raw Cypher + `nodeFromNeo4j()` for reads; `graph/GraphRepository.java` (Spring Data Neo4j) is used **only for writes**. This is deliberate — SDN's hydration overhead was measured and rejected for the read path. Don't unify them.
- **Auto-discovery via Spring `@Component` on detectors, no explicit registry.** Drop in a class, it's live. The `DetectorRegistry` exists to *introspect* the discovered set, not to register them. Don't replace with a manual registry.
- **CLI profile selection in `CodeIqApplication.main` (not via Picocli's mechanism).** It's a string `if/else` on the first arg, and it pre-empts Picocli to set the Spring profile *before* the context starts. Looks ugly; works correctly. SpotBugs flagged the original duplicate branches; the current version was deliberately collapsed.
- **`indexing` profile sets `WebApplicationType.NONE`** — meaning `mvn test` from the IDE without `@ActiveProfiles("test")` will try to start the web server and pin to ports. Always use `@ActiveProfiles("test")` on `@SpringBootTest`.
- **Frontend assets bundled into the JAR (`src/main/resources/static/`)** — no separate frontend deploy. Vite's `outDir: '../resources/static'` is the embed seam; don't move the SPA out of the JAR without re-architecting the deploy story.
- **`prop_*` Neo4j property prefix.** It's a deliberate namespacing scheme to separate domain properties from top-level node attributes (`id`, `kind`, `layer`, etc.). Don't rename.
