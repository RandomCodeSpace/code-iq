# Sub-project 1 — Resolver SPI + Java Pilot + Confidence Schema

> **Status:** Awaiting approval. Brainstormed 2026-04-27.
> **Authors:** brainstormed via `superpowers:brainstorming` with the project maintainer.
> **Audience:** the agent / engineer who will implement this. Every claim should be checkable against the codebase referenced by `CLAUDE.md` and `PROJECT_SUMMARY.md`.

## 1. Context

codeiq's detector layer is the right abstraction. The **layer below it** is the bottleneck: detectors receive a parse tree (ANTLR) or AST (JavaParser) but no resolved symbol table. As a result, edges like `CALLS`, `INJECTS`, `IMPLEMENTS`, `EXTENDS`, and many framework-specific edges are emitted *by name*, not by **resolved type**. Two same-named symbols across packages collapse into one node; `userService.findById(id)` resolves to whichever `findById` the detector happens to see first.

This is the architectural seam between "rich code map" and "ground-truth semantic graph." Every other planned improvement — TypeScript / Python / Go / Rust / C++ / C# resolution, framework-aware detection refactor, cross-framework false-positive harness — slots into this seam. Doing it second means inventing the seam ad-hoc inside whichever sub-project lands first, then retrofitting.

This spec covers **sub-project 1 of 8** in the larger "robust graph" decomposition:

| # | Scope | This spec? |
|---|---|---|
| 1 | Resolver SPI + Java pilot + confidence/provenance schema | **Yes** |
| 2 | TypeScript / JavaScript resolution | No |
| 3 | Python resolution | No |
| 4 | Go resolution | No |
| 5 | Rust / C++ / C# resolution | No |
| 6 | Framework-aware detection refactor | No |
| 7 | Cross-framework false-positive harness | No |
| 8 | MCP HTTP-streamable hardening (read-path) | No |

## 2. Goals

1. **Add a symbol-resolution stage** to the indexing pipeline, between parse and detect, that exposes a resolved symbol table to detectors.
2. **Wire a Java backend** using JavaParser's `JavaSymbolSolver`, with no new dependency tree (the solver is published alongside JavaParser).
3. **Add a confidence/provenance schema** (`Confidence` enum + `source` field) on every `CodeNode` and `CodeEdge`, round-tripped through Neo4j.
4. **Migrate 4–6 Java detectors** to use the resolver as proof of value: at least one Spring DI detector, one JPA detector, one messaging detector.
5. **Preserve backward compatibility:** all existing detectors compile and run unchanged. Resolution is opt-in per detector via `ctx.resolved()`.
6. **Preserve determinism:** resolver-stage output is byte-identical run-to-run, with the same input.
7. **Aggressive testing**, including adversarial inputs, concurrency stress, property-based, fuzz, mutation testing, and regression against the existing E2E quality bar.

## 3. Non-goals

- Maven / Gradle classpath JAR resolution beyond what `ReflectionTypeSolver` covers via the running JDK. (Possible follow-up: sub-project 1.5.)
- Resolution for non-Java languages. (Sub-projects 2–5.)
- Refactoring detectors to detect by resolved type rather than import-name. (Sub-project 6 — separate concern; a migrated detector here keeps its current detection mechanism, only resolving outgoing edges' targets more accurately.)
- Performance optimization beyond what the design naturally affords. (Defer until measured.)
- Changes to the serving layer (REST API, MCP tools, web UI).
- Changes to `application.yml` Spring-owned keys (CORS, Neo4j Bolt port, UI toggle).

## 4. Architecture

### 4.1 Pipeline shape

The current `index` and `analyze` pipelines look like:

```
discover → parse → detect → link → classify → store
```

After this sub-project, they become:

```
discover → parse → resolve → detect → link → classify → store
```

The resolve stage runs after `analyzer/StructuredParser` produces a parsed file and before the detector fan-out kicks off.

### 4.2 Resolver-pass placement

- **Bootstrapping:** `analyzer/Analyzer` (or `cli/IndexCommand`'s in-process pipeline) calls `ResolverRegistry.bootstrap(rootPath)` once per analysis run, before file iteration begins. The Java resolver uses this hook to build a single `CombinedTypeSolver` configured with sorted source roots and `ReflectionTypeSolver`. Other languages' resolvers (future sub-projects) plug into the same hook.
- **Per-file resolution:** for each file, after parse, the analyzer asks `ResolverRegistry.resolverFor(language)` for the matching resolver, calls `resolve(parsedFile)`, and stores the result on the `DetectorContext` as `Optional<Resolved>`.
- **Detector consumption:** detectors call `ctx.resolved()`. If present, the detector may emit edges with `Confidence.RESOLVED`; if absent, the detector falls through to its existing logic and emits `Confidence.SYNTACTIC` (when AST-based) or `Confidence.LEXICAL` (when regex-based).

### 4.3 Pipeline invariant

The new stage must not change *which files are analyzed* or *which detectors run for them*. It only enriches the input each detector sees. A regression here breaks every downstream count and statistic.

## 5. Components

### 5.1 New components

| Path | Type | Responsibility |
|---|---|---|
| `intelligence/resolver/SymbolResolver.java` | interface | SPI: `Set<String> getSupportedLanguages(); Resolved resolve(ParsedFile parsed) throws ResolutionException;` |
| `intelligence/resolver/Resolved.java` | interface (or sealed type) | Read-only resolution result for one file: per-symbol type info, resolved imports, declared types. Includes `Confidence sourceConfidence()` indicating the resolver's confidence in this particular result. |
| `intelligence/resolver/EmptyResolved.java` | record / class | Singleton "no resolution available" — returned for unsupported languages, disabled config, or resolution failure. |
| `intelligence/resolver/ResolverRegistry.java` | `@Component` | Auto-discovers `@Component` `SymbolResolver` beans (mirrors `DetectorRegistry`). Exposes `resolverFor(language)` and `bootstrap(rootPath)`. |
| `intelligence/resolver/ResolutionException.java` | exception | Wraps backend-specific failures (e.g. `JavaSymbolSolver` errors) with context (file path, language). |
| `intelligence/resolver/java/JavaSymbolResolver.java` | `@Component` | Wraps `JavaSymbolSolver`. Builds `CombinedTypeSolver` from sorted source roots + `ReflectionTypeSolver`. |
| `intelligence/resolver/java/JavaResolved.java` | record | Java-specific `Resolved` carrying JavaParser `TypeSolver` + per-AST resolved type info. |
| `intelligence/resolver/java/JavaSourceRootDiscovery.java` | helper | Discovers Java source roots from a project root (auto-detects `src/main/java`, `src/test/java`, multi-module via Maven `<module>` / Gradle `include`). Pure logic, unit-testable. |
| `model/Confidence.java` | enum | `LEXICAL` / `SYNTACTIC` / `RESOLVED` with a numeric mapping (0.6 / 0.8 / 0.95). Comparable. |
| `model/EdgeProvenance.java` *(optional, see §5.3)* | record | Optional richer provenance carrier; if not adopted, just use `String source` on `CodeEdge`. |

### 5.2 Changed components

| Path | Change | Rationale |
|---|---|---|
| `detector/DetectorContext.java` | Add `Optional<Resolved> resolved()` accessor. Defaults to `Optional.empty()`. Existing constructors keep working. | Detector opt-in path. |
| `model/CodeNode.java` | Add `Confidence confidence` and `String source` fields. `source` filled in by detector base classes (detector class simple name). `confidence` set per parser type (see §5.3): `AbstractRegexDetector` → `LEXICAL`, `AbstractJavaParserDetector` / `AbstractAntlrDetector` / `AbstractStructuredDetector` → `SYNTACTIC`. Detectors override to `RESOLVED` when emitting an edge derived from `ctx.resolved()`. | Confidence/provenance schema. |
| `model/CodeEdge.java` | Same as `CodeNode`. | Same. |
| `graph/GraphStore.java` | `bulkSave` writes `prop_confidence` and `prop_source`; `nodeFromNeo4j` / `edgeFromNeo4j` restore them. | Round-trip the new fields. |
| `cache/AnalysisCache.java` | Bump `CACHE_VERSION` from 4 to 5. Add `confidence` and `source` columns to `nodes` and `edges` tables. | Schema change requires cache reset. |
| `analyzer/Analyzer.java` | Insert resolve step. `bootstrapResolvers(rootPath)` once; `resolverFor(language).resolve(parsed)` per file. | Pipeline integration. |
| `cli/IndexCommand.java` | Mirror `Analyzer`'s resolver bootstrap (the in-process H2 batched pipeline). | Both code paths must integrate. |
| 4–6 Java detectors (see §5.4) | Use `ctx.resolved()`. Emit `Confidence.RESOLVED` when present; existing path emits `Confidence.SYNTACTIC`. | Proof of value. |
| `pom.xml` | Add `com.github.javaparser:javaparser-symbol-solver-core` (Apache-2.0, version-pinned to match `javaparser-core`). Resolve **latest stable matching version** at implementation time. Add `net.jqwik:jqwik` (test scope, EPL-2.0) for property-based tests. | New deps. |
| `codeiq.yml` schema (`docs/codeiq.yml.example`) | Document the new `intelligence.symbol_resolution.java` keys. | Surface the new config. |
| `config/CodeIqConfig.java` (or unified-config equivalent) | Bind the new keys. | Enable the toggles. |

### 5.3 Confidence / provenance — schema decisions

- **Storage shape:** the simplest viable model is two scalar fields on every `CodeNode` and `CodeEdge`:
  - `confidence: Confidence` (enum, non-null). The default is set by the detector's base class — not a single hardcoded value — based on the parser used:
    - `AbstractRegexDetector` → `LEXICAL` (pattern-only, no AST)
    - `AbstractJavaParserDetector` / `AbstractAntlrDetector` / `AbstractStructuredDetector` / `AbstractPythonAntlrDetector` / `AbstractTypeScriptDetector` / `AbstractJavaMessagingDetector` / `AbstractPythonDbDetector` → `SYNTACTIC` (AST or parse tree, no symbol resolution)
    - Detector overrides to `RESOLVED` for any edge derived from `ctx.resolved()`.
  - `source: String` (non-null; detector class simple name, e.g. `"SpringServiceDetector"`)
- **Numeric access:** consumers (Cypher queries, MCP tools, the SPA) get a numeric value via `Confidence.score()` (0.6 / 0.8 / 0.95). The mapping is a static lookup; the enum is the authoritative form.
- **Future extensibility:** if richer provenance is needed later (e.g. resolver name, resolution timestamp), extend with optional `prop_resolver` etc. — the enum + source design does not preclude this. Don't pre-build for it.
- **MCP / API surface:** `confidence` and `source` are passthrough fields in node/edge JSON serialization. No new endpoints. Cypher filters can use `WHERE n.confidence = 'RESOLVED'` once the schema lands.

### 5.4 Detector migration candidates (4–6)

Final selection happens at implementation time based on which gives the clearest signal in `spring-petclinic`. Likely set:

| Detector | Path | Why |
|---|---|---|
| `SpringServiceDetector` | `detector/jvm/java/SpringServiceDetector.java` | `@Autowired UserService` — needs to resolve `UserService` to its actual type for cross-class wiring. Highest visibility win. |
| `SpringRepositoryDetector` | `detector/jvm/java/SpringRepositoryDetector.java` | Repository interfaces extending `JpaRepository<T, ID>` — resolving `T` lets us link the repo to the entity. |
| `JpaEntityDetector` | `detector/jvm/java/JpaEntityDetector.java` | `@OneToMany List<Owner>` — resolving the generic argument links entity-to-entity correctly. |
| `JpaRepositoryDetector` | `detector/jvm/java/JpaRepositoryDetector.java` | Same as Spring repo, deeper. |
| `KafkaListenerDetector` | `detector/jvm/java/KafkaListenerDetector.java` | Topic resolution from `@KafkaListener(topics = TOPIC_CONST)`. |
| `SpringRestDetector` | `detector/jvm/java/SpringRestDetector.java` | `@RequestBody UserDto dto` — resolving `UserDto` enables `MAPS_TO` edges from endpoint to entity. |

Six is the upper bound; if four are sufficient to demonstrate measurable quality lift on petclinic, the rest can be migrated in follow-up PRs without changing this spec.

## 6. Data flow (per analysis run)

```
1. cli/{Index,Analyze}Command.call() → analyzer/Analyzer.run(rootPath)
     1.1. ResolverRegistry.bootstrap(rootPath)
            → JavaSymbolResolver.bootstrap()
                 - JavaSourceRootDiscovery.discover(rootPath) → sorted List<Path>
                 - new CombinedTypeSolver(
                       new ReflectionTypeSolver(),
                       sorted source roots wrapped in JavaParserTypeSolver)
                 - new JavaSymbolSolver(combinedTypeSolver)
                 - configure JavaParser default ParserConfiguration with the solver
2. For each discovered file (virtual thread):
     2.1. StructuredParser.parse(file) → ParsedFile (Java → CompilationUnit; others → existing types)
     2.2. resolved = ResolverRegistry.resolverFor(file.language()).resolve(parsedFile)
            (returns EmptyResolved.INSTANCE for languages without a registered resolver)
     2.3. ctx = DetectorContext.builder()...resolved(resolved)...build()
     2.4. for each Detector matching language: detector.detect(ctx)
3. GraphBuilder.flush() → AnalysisCache (or → GraphStore on enrich)
     - Each node and edge carries Confidence + source
     - Round-tripped via prop_confidence / prop_source in Neo4j
```

## 7. Configuration surface

New keys in `codeiq.yml`:

```yaml
intelligence:
  symbol_resolution:
    java:
      enabled: true
      source_roots: auto    # or explicit list of paths relative to repo root
      jdk_reflection: true  # ReflectionTypeSolver — needs JDK on classpath (always true for codeiq's runtime)
      # bootstrap_timeout_seconds: 30 (kill switch if solver hangs)
      # max_per_file_resolve_ms: 500 (per-file resolution timeout)
```

**Defaults:**
- `enabled: true` — most users want correctness > raw speed.
- `source_roots: auto` — discovery covers Maven (`src/main/java`, `src/test/java`, multi-module via `<module>` in `pom.xml`), Gradle (similar), and plain layouts.
- `jdk_reflection: true`.
- `bootstrap_timeout_seconds: 30`.
- `max_per_file_resolve_ms: 500`.

**Env overrides:** `CODEIQ_INTELLIGENCE_SYMBOL_RESOLUTION_JAVA_ENABLED=false` etc.

**Config validation:** `codeiq config validate` must reject invalid combinations (e.g. `enabled: true` with empty `source_roots: []`).

## 8. Backward compatibility

- All existing `Detector` implementations compile and run unchanged. `ctx.resolved()` returns `Optional.empty()` for them by default (they never call it).
- Existing tests must pass with `intelligence.symbol_resolution.java.enabled: false`. **Mandatory.** Two sub-cases:
  - **Logical-content tests** (assert on node IDs, edge counts, specific property values): pass unchanged.
  - **JSON-snapshot / golden-file tests** (assert on full serialized output): will shift by exactly two new fields per node/edge (`confidence`, `source`). These get a **one-time refresh** during implementation, with a separate commit so the diff is reviewable. The refresh must produce only those two added fields per record — any other diff is a bug.
- With `enabled: true`, logical-content tests still pass — but some node/edge counts may shift **by design** (resolved-mode detectors emit different / additional edges that the lexical fallback could not produce). Expected diffs are recorded in the implementation plan and PR description.
- `CACHE_VERSION` bump from 4 to 5 wipes old `.codeiq/cache/` on first run. Documented in `CHANGELOG.md` under `[Unreleased]` as a breaking cache change. End users lose nothing meaningful; the cache rebuilds on the next `index` run.

## 9. Performance budget

| Stage | Cost | Notes |
|---|---|---|
| Resolver bootstrap | 2–5 s on a medium repo | One-time per run. Cached `CombinedTypeSolver` reused across files. |
| Per-Java-file resolve | 50–200 ms typical | Net +30–60% on Java analysis time. |
| Per-non-Java-file resolve | 0 (EmptyResolved) | No-op. |
| Memory overhead | tens to low hundreds of MB | `CombinedTypeSolver` caches resolved type info; bounded by source-root size. |
| Determinism cost | none | Sorted source roots add ms-scale. |

For a 44 K-file codebase:
- Today: index ~220 s.
- After: index ~280–350 s (Java-heavy repos worst case). Acceptable.
- Mitigation: `intelligence.symbol_resolution.java.enabled: false` for raw-speed scans.

**Performance gate:** if resolver bootstrap exceeds 10 s on `spring-petclinic`, the implementation has a bug — investigate before merge.

## 10. Determinism guarantees

- `JavaSourceRootDiscovery.discover(rootPath)` returns roots sorted alphabetically.
- `CombinedTypeSolver` member solvers added in the sorted order.
- `ResolverRegistry` exposes resolvers in stable iteration order (Spring `@Component` collection sorted by simple class name).
- `Resolved` value-types use `TreeMap` / sorted `List` for any iteration-order-sensitive data.
- New determinism test (mandatory): run resolver twice on the same input via separate JVM invocations, assert byte-identical serialized output. Mirrors existing detector convention.

## 11. Error handling

| Failure | Behavior |
|---|---|
| Source root configured but missing | Log WARN, drop from solver list, continue. |
| Source root contains no Java files | Drop from solver list, continue. |
| `CombinedTypeSolver` construction throws | Log ERROR with classpath context, fall back to `EmptyResolved` for all files (resolver disabled for this run), increment a metric. Do **not** abort the analysis. |
| Per-file `resolve(parsedFile)` throws | Log DEBUG (these are expected for malformed sources), return `EmptyResolved` for that file, continue. |
| Per-file resolution exceeds `max_per_file_resolve_ms` | Cancel via virtual-thread interruption, return `EmptyResolved` for that file, count timeout in metrics. |
| Bootstrap exceeds `bootstrap_timeout_seconds` | Abort bootstrap, fall back to `EmptyResolved` for the run, log ERROR. Run continues without resolution. |
| Detector calls `ctx.resolved().get()` and crashes | Caught by existing per-detector `try/catch` in `Analyzer` — file is skipped, detector is logged, run continues. (Existing behavior.) |

## 12. Aggressive testing strategy

This section is binding. Every layer below is mandatory for sub-project 1; the same template applies to sub-projects 2–8.

### Layer 1 — Resolver unit tests (pure, fast)

For `JavaSymbolResolver`, with one synthetic source tree per test:

- Empty file (zero declarations).
- Single class with no imports.
- Class with multiple methods of varying signatures (overloads).
- Class with generics (≥3 levels of nesting: `Map<String, List<Set<UUID>>>`).
- Inner classes (static, non-static, anonymous, local).
- Lambda expressions and method references.
- Records and sealed classes (Java 25).
- Enum with abstract methods.
- Interface with default methods.
- Abstract class.
- Annotations (definition + use).
- Imports: explicit, static, wildcard, missing target, unused.
- Cyclic imports between two files (legal in Java) — both resolve.
- Two classes with the same simple name in different packages — both resolve to distinct nodes.
- Symbol defined in JDK (`Optional`, `Stream`, `List`) — resolves via `ReflectionTypeSolver`.
- Multi-source-root: a class in `src/main/java` referencing one in `src/test/java`.

Expected: every test asserts the *exact* `Resolved` content via golden files committed under `src/test/resources/intelligence/resolver/java/`.

### Layer 2 — Detector × resolver integration tests

For each migrated detector:
- **Resolved-mode positive:** with resolver enabled, assert resolved-only edges that the lexical fallback could not produce (e.g. `INJECTS` edges to the *correct* `UserService` of two same-named classes in different packages).
- **Fallback-mode positive:** with resolver disabled, assert logical-content output identical to the pre-spec baseline (modulo the additive `confidence` and `source` fields per §8).
- **Mixed mode:** simulate resolver failure on half the files; the other half emits resolved edges, the failing half emits fallback edges. Both labeled with correct `Confidence`.

### Layer 3 — Concurrency stress

- 1000 synthetic Java files resolved on virtual threads. Assert: no exceptions, no deadlocks, no thread starvation, total throughput within 2× of sequential baseline. Output identical to sequential run (sort-then-compare).
- Resolver bootstrap happens **once** even if 50 threads call `resolverFor` simultaneously at startup. Verify via mock + invocation count.

### Layer 4 — Memory / pathological inputs

- 10 000-line synthetic class file: resolves under -Xmx512m.
- File with 1000 imports (most unresolved): resolves without OOM; produces the expected partial result.
- Deep generic nesting (10 levels deep): resolves; runtime ≤ 1 s.

### Layer 5 — Adversarial inputs

- File with syntax errors (parser fails): resolver never invoked; `Analyzer` continues.
- File mis-tagged as Java but actually Kotlin / Groovy / random bytes: parser fails first; resolver never sees it.
- Mixed source root with `.java` and unrelated files: only `.java` files enter the solver.
- `ReflectionTypeSolver` simulated as unavailable (test injects null JDK classpath): resolver works at reduced fidelity, returns `Confidence.SYNTACTIC` for JDK-dependent symbols.

### Layer 6 — Determinism

- Run resolver 10 times against the same input on the same JVM. Assert byte-identical serialized graphs.
- Run resolver against the same input, with source roots passed in a different order. Assert byte-identical output (we sort internally).
- Run on cold and warm JVMs. Identical.

### Layer 7 — E2E quality regression (gating)

- `E2EQualityTest` against `spring-petclinic` ground truth (`src/test/resources/e2e/ground-truth-petclinic.json`):
  - With `enabled: false`: logical-content output identical to the pre-spec baseline (modulo the additive `confidence` and `source` fields per record — see §8). Mandatory regression gate.
  - With `enabled: true`: edge precision / recall **measurably up** vs. the `enabled: false` baseline. The implementation plan will record before/after numbers; this spec demands measurable improvement with no regressions on other metrics in the ground-truth file.
- Full `mvn test` green.
- Full `mvn verify` green (SpotBugs, dependency-check). May skip locally; CI is authoritative.

### Layer 8 — Property-based / fuzz (jqwik)

- New test scope dependency: `net.jqwik:jqwik` (latest stable, EPL-2.0). License is EPL-2.0 — flag for explicit approval; if rejected, swap for a permissive alternative (or hand-write generators). **License decision deferred to implementation time** — see §15 below.
- Generators produce small synthetic Java source strings (within JavaParser's grammar). Invariants tested:
  - Resolver never throws an unchecked exception (only `ResolutionException` or returns `EmptyResolved`).
  - Resolver always terminates within `max_per_file_resolve_ms`.
  - Same input → same output (deterministic).
  - Editing an unrelated file in a different source root never changes the resolution of file F.

### Layer 9 — Mutation testing (PIT)

- Add PIT mutation testing as a **non-gating** Maven goal (e.g. `mvn -P mutation pitest:mutationCoverage`).
- Target: 80% mutant kill rate on the new packages (`io.github.randomcodespace.iq.intelligence.resolver.*`, `io.github.randomcodespace.iq.model.Confidence`).
- Not bound to `mvn verify` — runs on demand. Used as a code-quality signal during PR review.

### Test-data hygiene

- Synthetic Java sources for unit tests live under `src/test/resources/intelligence/resolver/java/<scenario>/...`.
- Each scenario has a `README.md` explaining intent (one paragraph).
- Golden output (`expected.json`) checked in. Updated only via a documented refresh script.

## 13. Acceptance criteria

Sub-project 1 is "done" when **all** of the following are true on the feature branch:

1. **All tests in §12 layers 1–7 pass.** Layers 8 and 9 are non-gating but must run cleanly.
2. **`mvn verify` green** on CI (full Java CI workflow, including SpotBugs and OWASP dependency-check).
3. **No logical-content regression** in any existing test (`mvn test` green with `enabled: false`). Snapshot tests refreshed in a separate commit per §8; the refresh diff must be limited to the two additive fields per record.
4. **E2E petclinic precision/recall measurably improved** with `enabled: true`. The PR description records before/after numbers.
5. **`CHANGELOG.md`** updated under `[Unreleased]` with a one-paragraph entry naming the new config keys, the schema additions, and the cache-version bump.
6. **`CLAUDE.md`** updated under "Gotchas" to note: confidence/provenance is now mandatory on every node/edge; the resolver pass is part of the pipeline; cache version is 5.
7. **`PROJECT_SUMMARY.md`** "Tech stack" + "Gotchas" updated.
8. **Determinism re-verified** on the migrated detectors (existing determinism tests still pass; new ones added per §12 layer 6).
9. **No new dependencies with non-permissive licenses** (Apache-2.0 / MIT / BSD only without explicit user sign-off; jqwik EPL-2.0 needs explicit OK or replacement — see §15).
10. **No new High/Critical CVEs** introduced (`mvn verify` security gate green).

## 14. Risks & mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| `JavaSymbolSolver` performance worse than budgeted | Medium | Pipeline unusable for very large repos | `enabled: false` escape hatch; performance gate in §9; profile before merge |
| Source-root auto-discovery wrong on niche project layouts | Medium | Resolver falls back to `EmptyResolved` silently → user sees no improvement | Explicit `source_roots: [list]` override; clear log message at WARN when discovery yields zero roots; `codeiq config explain` shows discovered roots |
| Confidence schema change breaks consumers (SPA, MCP clients) | Low (additive only) | API drift | Fields are additive; default `LEXICAL`/detector-name. Existing consumers ignore unknown fields per Jackson config (`FAIL_ON_UNKNOWN_PROPERTIES = false`). |
| Cache-version bump surprises users | Low | One-time slow re-index after upgrade | `CHANGELOG` entry; user-facing log line on first run after bump |
| jqwik EPL-2.0 license blocked by user policy | Low (already flagged in defaults) | No property-based tests in layer 8 | Hand-write generators or pick a permissive alternative; flagged for decision at impl time |
| `JavaSymbolSolver` panics on Java 25 idioms (records, sealed, pattern-match) | Medium | Resolver failure on modern Java | Per-file resolution failures are caught (§11); track upstream JavaParser issues; pin to latest JavaParser version |
| Cross-class resolution still ambiguous with same-named symbols across modules | Medium | False matches even with resolver | Track via E2E quality numbers; flag for sub-project 1.5 (Maven/Gradle classpath JAR resolution) if material |

## 15. Dependency decisions

To be resolved at implementation time (NOT in this spec):

1. **`javaparser-symbol-solver-core` exact version.** Resolve the latest stable version compatible with `javaparser-core` (currently 3.28.0 per CLAUDE.md). Use `context7` MCP first; fall back to Maven Central.
2. **`net.jqwik:jqwik` license (EPL-2.0).** Per `~/.claude/rules/dependencies.md`: "Permissive licenses (MIT/Apache/BSD) preferred. GPL/AGPL flagged for approval." EPL-2.0 is not GPL/AGPL but is also not on the preferred list. Default plan: ask the user once at implementation time; if blocked, swap for hand-rolled generators or another permissive property-test framework. **Will not add jqwik silently.**
3. **PIT mutation testing dep.** Apache-2.0; safe to add as a non-default Maven profile.

## 16. Out of scope (cross-reference)

- **TypeScript / JavaScript / Python / Go / Rust / C++ / C# resolution** — sub-projects 2–5. They will plug into the SPI defined here.
- **Detect-by-resolved-type detector refactor** — sub-project 6. Migrated detectors here keep their current detection mechanism; only their *outgoing edges* benefit from resolution.
- **Cross-framework false-positive harness** — sub-project 7.
- **MCP HTTP-streamable hardening** — sub-project 8.
- **Maven/Gradle classpath JAR resolution** — possible sub-project 1.5 if E2E quality numbers reveal a gap.

## 17. Implementation sequencing (informational, plan owns the detail)

The plan that follows this spec will sequence work as:
1. Schema changes (`Confidence` enum, `CodeNode`/`CodeEdge` fields, Neo4j round-trip, `AnalysisCache` schema + version bump).
2. SPI scaffolding (`SymbolResolver`, `Resolved`, `EmptyResolved`, `ResolverRegistry`).
3. Java backend (`JavaSourceRootDiscovery`, `JavaSymbolResolver`, `JavaResolved`).
4. Pipeline wiring (`Analyzer`, `IndexCommand`).
5. Detector migration (one detector at a time, each with new + existing tests passing).
6. Aggressive testing layers (1–9 in order, layers 8/9 may run in parallel with 5–7).
7. Doc updates (`CHANGELOG`, `CLAUDE.md`, `PROJECT_SUMMARY.md`).
8. PR ready for human review when all acceptance criteria green.

## 18. References

- [`PROJECT_SUMMARY.md`](../../PROJECT_SUMMARY.md) — repo-wide entry point.
- [`CLAUDE.md`](../../CLAUDE.md) — canonical internals.
- [`docs/project/architecture.md`](../project/architecture.md) — pipeline + components, including the package layering rule that detectors may not depend on `analyzer/`.
- [`docs/project/data-model.md`](../project/data-model.md) — `NodeKind`, `EdgeKind`, Neo4j schema, H2 cache schema.
- [`docs/project/conventions.md`](../project/conventions.md) — detector authoring, base classes, "don't refactor" rules.
- [`docs/project/build-and-run.md`](../project/build-and-run.md) — Maven, ANTLR codegen, frontend bundling.
- JavaParser symbol-solver documentation: resolve via `context7` MCP at implementation time.
- Sourcegraph SCIP and GitHub Stack Graphs as comparable patterns (informational only — not adopted in sub-project 1).
