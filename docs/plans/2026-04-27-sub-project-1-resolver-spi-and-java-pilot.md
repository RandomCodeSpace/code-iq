# Sub-project 1 — Resolver SPI + Java Pilot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a symbol-resolution stage between parse and detect, ship a Java backend wrapping JavaParser's `JavaSymbolSolver`, attach `Confidence` + `source` to every node/edge with Neo4j round-trip and an H2 cache version bump, migrate 4–6 Java detectors as proof of value, and bake in 9 layers of aggressive testing — without changing what existing detectors do.

**Architecture:** New SPI under `intelligence/resolver/` with a per-language registry mirroring `DetectorRegistry`. The Java backend wraps JavaParser `JavaSymbolSolver` configured from sorted source roots + `ReflectionTypeSolver`. Detectors opt-in via `ctx.resolved()` returning `Optional<Resolved>`; existing detectors compile and behave identically when resolution is absent or disabled.

**Tech stack:** Java 25, Spring Boot 4.0.5, JavaParser 3.28.0 + new `javaparser-symbol-solver-core`, Neo4j Embedded 2026.02.3, H2 (cache), JUnit 5 (existing test scope), `net.jqwik:jqwik` (new test scope, pending license OK), PIT mutation testing (new non-default Maven profile).

**Reference:** Full design in [`../specs/2026-04-27-resolver-spi-and-java-pilot-design.md`](../specs/2026-04-27-resolver-spi-and-java-pilot-design.md). Read it before starting — every task here has a corresponding section in the spec.

**Working branch:** `feat/sub-project-1-resolver-spi-and-java-pilot` (already created and ahead of `main` by the spec + doc-sync commits).

---

## File Structure

### NEW files (create)

| Path | Responsibility |
|---|---|
| `src/main/java/io/github/randomcodespace/iq/model/Confidence.java` | Enum `LEXICAL` / `SYNTACTIC` / `RESOLVED` + numeric `score()` |
| `src/main/java/io/github/randomcodespace/iq/intelligence/resolver/SymbolResolver.java` | SPI interface |
| `src/main/java/io/github/randomcodespace/iq/intelligence/resolver/Resolved.java` | Per-file resolution result interface |
| `src/main/java/io/github/randomcodespace/iq/intelligence/resolver/EmptyResolved.java` | Singleton for "no resolution" cases |
| `src/main/java/io/github/randomcodespace/iq/intelligence/resolver/ResolutionException.java` | Wraps backend failures |
| `src/main/java/io/github/randomcodespace/iq/intelligence/resolver/ResolverRegistry.java` | Spring auto-discovery + `bootstrap(rootPath)` + `resolverFor(language)` |
| `src/main/java/io/github/randomcodespace/iq/intelligence/resolver/java/JavaSourceRootDiscovery.java` | Detect Maven/Gradle/plain source roots from a project root |
| `src/main/java/io/github/randomcodespace/iq/intelligence/resolver/java/JavaResolved.java` | Java-specific `Resolved` carrying `JavaSymbolSolver` reference + per-CU info |
| `src/main/java/io/github/randomcodespace/iq/intelligence/resolver/java/JavaSymbolResolver.java` | `@Component`, builds `CombinedTypeSolver`, resolves Java files |
| `src/test/java/io/github/randomcodespace/iq/model/ConfidenceTest.java` | Unit test |
| `src/test/java/io/github/randomcodespace/iq/intelligence/resolver/ResolverRegistryTest.java` | Auto-discovery + bootstrap tests |
| `src/test/java/io/github/randomcodespace/iq/intelligence/resolver/java/JavaSourceRootDiscoveryTest.java` | Source-root discovery on synthetic layouts |
| `src/test/java/io/github/randomcodespace/iq/intelligence/resolver/java/JavaSymbolResolverTest.java` | Resolver unit tests (Layer 1) — 15+ scenarios |
| `src/test/java/io/github/randomcodespace/iq/intelligence/resolver/java/JavaSymbolResolverConcurrencyTest.java` | Layer 3 stress |
| `src/test/java/io/github/randomcodespace/iq/intelligence/resolver/java/JavaSymbolResolverPathologicalTest.java` | Layer 4 |
| `src/test/java/io/github/randomcodespace/iq/intelligence/resolver/java/JavaSymbolResolverAdversarialTest.java` | Layer 5 |
| `src/test/java/io/github/randomcodespace/iq/intelligence/resolver/java/JavaSymbolResolverDeterminismTest.java` | Layer 6 |
| `src/test/java/io/github/randomcodespace/iq/intelligence/resolver/java/JavaSymbolResolverPropertyTest.java` | Layer 8 (jqwik) |
| `src/test/resources/intelligence/resolver/java/<scenario>/...` | Synthetic Java sources for unit tests |

### CHANGED files (modify)

| Path | Change |
|---|---|
| `src/main/java/io/github/randomcodespace/iq/model/CodeNode.java` | Add `confidence: Confidence`, `source: String`. Round-trippable. |
| `src/main/java/io/github/randomcodespace/iq/model/CodeEdge.java` | Same as `CodeNode`. |
| `src/main/java/io/github/randomcodespace/iq/graph/GraphStore.java` | Write/read `prop_confidence`, `prop_source`. Update `nodeFromNeo4j`, `edgeFromNeo4j`. |
| `src/main/java/io/github/randomcodespace/iq/cache/AnalysisCache.java` | Bump `CACHE_VERSION` 4→5. Add columns. |
| `src/main/java/io/github/randomcodespace/iq/detector/DetectorContext.java` | Add `Optional<Resolved> resolved()` + builder support. |
| `src/main/java/io/github/randomcodespace/iq/detector/AbstractRegexDetector.java` | Set default `Confidence.LEXICAL` on emitted nodes/edges. |
| `src/main/java/io/github/randomcodespace/iq/detector/AbstractJavaParserDetector.java` | Set default `Confidence.SYNTACTIC`. |
| `src/main/java/io/github/randomcodespace/iq/detector/AbstractAntlrDetector.java` | Set default `Confidence.SYNTACTIC`. |
| `src/main/java/io/github/randomcodespace/iq/detector/AbstractStructuredDetector.java` | Set default `Confidence.SYNTACTIC`. |
| `src/main/java/io/github/randomcodespace/iq/analyzer/Analyzer.java` | Wire ResolverRegistry bootstrap + per-file resolve. |
| `src/main/java/io/github/randomcodespace/iq/cli/IndexCommand.java` | Mirror `Analyzer` in the H2 batched pipeline. |
| `src/main/java/io/github/randomcodespace/iq/config/CodeIqConfig.java` (or unified equivalent) | Bind new `intelligence.symbol_resolution.java.*` keys. |
| `src/main/java/io/github/randomcodespace/iq/detector/jvm/java/SpringServiceDetector.java` | Use `ctx.resolved()` for `INJECTS` edge resolution. |
| `src/main/java/io/github/randomcodespace/iq/detector/jvm/java/SpringRepositoryDetector.java` | Use `ctx.resolved()` for entity-type linking. |
| `src/main/java/io/github/randomcodespace/iq/detector/jvm/java/JpaEntityDetector.java` | Use `ctx.resolved()` for `MAPS_TO` between entities. |
| `src/main/java/io/github/randomcodespace/iq/detector/jvm/java/JpaRepositoryDetector.java` | Same as Spring repo, deeper. |
| `src/main/java/io/github/randomcodespace/iq/detector/jvm/java/KafkaListenerDetector.java` | Resolve topic constants. |
| `src/main/java/io/github/randomcodespace/iq/detector/jvm/java/SpringRestDetector.java` | Resolve `@RequestBody` types for `MAPS_TO` edges. |
| `src/test/java/io/github/randomcodespace/iq/detector/jvm/java/<MigratedDetector>Test.java` | Add resolved-mode + fallback-mode + mixed-mode assertions. |
| `pom.xml` | Add `javaparser-symbol-solver-core` (latest stable matching `javaparser-core`) + `net.jqwik:jqwik` (test scope, pending license OK). PIT in non-default profile. |
| `docs/codeiq.yml.example` | Document `intelligence.symbol_resolution.java.*` keys. |
| `CHANGELOG.md` | Expand `[Unreleased]` entry once features are integrated. |
| `CLAUDE.md` | "Gotchas" addition: confidence/provenance is now mandatory; resolver pass exists; cache version 5. |
| `PROJECT_SUMMARY.md` | Tech stack + Gotchas update. |

---

## How to use this plan

- Each task is one logical commit (or small commit chain).
- Each step inside a task is 2–5 minutes and ends with verifiable output.
- Tests come first (TDD). Run them, see them fail, then implement, run them, see them pass, commit.
- Determinism tests are mandatory for every detector that gets migrated (Phase 6) and for the resolver itself (Task 30 / Layer 6).
- Frequent commits — one per task minimum, sometimes more.
- Unless noted, **all commands run from the repo root** `/home/dev/projects/codeiq`.

**Resume rule:** if interrupted mid-task, the next session re-runs the test command from the unfinished step to confirm where it stopped, then continues.

---

## Phase 1 — Schema foundation (Tasks 1–7)

### Task 1: `Confidence` enum

**Files:**
- Create: `src/main/java/io/github/randomcodespace/iq/model/Confidence.java`
- Test: `src/test/java/io/github/randomcodespace/iq/model/ConfidenceTest.java`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/io/github/randomcodespace/iq/model/ConfidenceTest.java
package io.github.randomcodespace.iq.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfidenceTest {

    @Test
    void scoreMappingIsStable() {
        assertEquals(0.6, Confidence.LEXICAL.score(),  1e-9);
        assertEquals(0.8, Confidence.SYNTACTIC.score(), 1e-9);
        assertEquals(0.95, Confidence.RESOLVED.score(), 1e-9);
    }

    @Test
    void naturalOrderingMatchesScore() {
        assertTrue(Confidence.LEXICAL.compareTo(Confidence.SYNTACTIC) < 0);
        assertTrue(Confidence.SYNTACTIC.compareTo(Confidence.RESOLVED) < 0);
    }

    @Test
    void valueOfNullIsRejected() {
        assertThrows(NullPointerException.class, () -> Confidence.fromString(null));
    }

    @Test
    void fromStringIsCaseInsensitive() {
        assertEquals(Confidence.RESOLVED, Confidence.fromString("resolved"));
        assertEquals(Confidence.RESOLVED, Confidence.fromString("RESOLVED"));
        assertEquals(Confidence.LEXICAL, Confidence.fromString("LeXiCaL"));
    }

    @Test
    void fromStringRejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> Confidence.fromString("perfect"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
mvn test -Dtest=ConfidenceTest -Dfrontend.skip=true -Ddependency-check.skip=true -q
```

Expected: compile error — `Confidence` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
// src/main/java/io/github/randomcodespace/iq/model/Confidence.java
package io.github.randomcodespace.iq.model;

import java.util.Objects;

/**
 * Confidence in the truth of a node or edge, based on the parser pipeline that
 * produced it. Lower means the assertion is from text patterns; higher means
 * the assertion is backed by parsed structure or resolved symbol types.
 *
 * <p>Comparable: {@code LEXICAL} &lt; {@code SYNTACTIC} &lt; {@code RESOLVED}.
 *
 * <p>Numeric mapping (via {@link #score()}) is stable and intended for Cypher /
 * MCP / SPA filtering. The enum itself is the authoritative form.
 */
public enum Confidence {
    /** Pattern-only match (regex). */
    LEXICAL(0.6),
    /** AST or parse tree, no symbol resolution. */
    SYNTACTIC(0.8),
    /** Resolved via a {@code SymbolResolver}. */
    RESOLVED(0.95);

    private final double score;

    Confidence(double score) {
        this.score = score;
    }

    public double score() {
        return score;
    }

    public static Confidence fromString(String value) {
        Objects.requireNonNull(value, "Confidence value must not be null");
        for (Confidence c : values()) {
            if (c.name().equalsIgnoreCase(value)) {
                return c;
            }
        }
        throw new IllegalArgumentException("Unknown Confidence: " + value);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
mvn test -Dtest=ConfidenceTest -Dfrontend.skip=true -Ddependency-check.skip=true -q
```

Expected: 5/5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/io/github/randomcodespace/iq/model/Confidence.java \
        src/test/java/io/github/randomcodespace/iq/model/ConfidenceTest.java
git commit -m "feat(model): add Confidence enum (LEXICAL/SYNTACTIC/RESOLVED)

Per sub-project 1 spec §5.3. Numeric score() mapping stable (0.6/0.8/0.95).
Comparable by natural order. fromString() is case-insensitive and rejects
null + unknown values.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Add `confidence` + `source` to `CodeNode`

**Files:**
- Modify: `src/main/java/io/github/randomcodespace/iq/model/CodeNode.java`
- Test: existing `CodeNodeTest.java` (or create one if missing) — add round-trip assertion via `equals`/`hashCode`

- [ ] **Step 1: Read current `CodeNode.java`** to see its shape (record vs class, builder vs constructor).

```bash
sed -n '1,80p' src/main/java/io/github/randomcodespace/iq/model/CodeNode.java
```

- [ ] **Step 2: Write failing test**

```java
// src/test/java/io/github/randomcodespace/iq/model/CodeNodeConfidenceTest.java
package io.github.randomcodespace.iq.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodeNodeConfidenceTest {

    @Test
    void newNodeCarriesConfidenceAndSource() {
        CodeNode n = CodeNode.builder()
                .id("node:foo:class:Foo")
                .kind(NodeKind.CLASS)
                .label("Foo")
                .confidence(Confidence.SYNTACTIC)
                .source("MyDetector")
                .build();
        assertEquals(Confidence.SYNTACTIC, n.confidence());
        assertEquals("MyDetector", n.source());
    }

    @Test
    void confidenceDefaultsToLexicalIfUnset() {
        CodeNode n = CodeNode.builder()
                .id("node:foo:class:Foo")
                .kind(NodeKind.CLASS)
                .label("Foo")
                .source("MyDetector")
                .build();
        assertEquals(Confidence.LEXICAL, n.confidence(),
            "missing confidence falls back to LEXICAL — least committal");
    }

    @Test
    void sourceIsRequired() {
        assertThrows(IllegalStateException.class, () -> CodeNode.builder()
                .id("node:foo:class:Foo")
                .kind(NodeKind.CLASS)
                .label("Foo")
                .build(),
            "source is mandatory — every node knows which detector emitted it");
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
mvn test -Dtest=CodeNodeConfidenceTest -Dfrontend.skip=true -Ddependency-check.skip=true -q
```

Expected: compile error — `confidence(...)` and `source(...)` not on builder.

- [ ] **Step 4: Add fields + builder methods to `CodeNode`**

Add fields, builder setters, getter accessors, equals/hashCode coverage. Field defaults: `confidence = Confidence.LEXICAL`, `source` required (validated in builder).

(Code shown verbatim once existing structure is read in Step 1; the change must preserve all existing tests by leaving every other field's behavior unchanged.)

- [ ] **Step 5: Run all model tests to verify nothing else regressed**

```bash
mvn test -Dtest='io.github.randomcodespace.iq.model.*' -Dfrontend.skip=true -Ddependency-check.skip=true -q
```

Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/github/randomcodespace/iq/model/CodeNode.java \
        src/test/java/io/github/randomcodespace/iq/model/CodeNodeConfidenceTest.java
git commit -m "feat(model): add confidence + source to CodeNode

Per sub-project 1 spec §5.2. Both fields non-null. Confidence defaults to
LEXICAL (least committal). Source is mandatory — every node knows which
detector emitted it.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Add `confidence` + `source` to `CodeEdge`

Same shape as Task 2, but on `CodeEdge`. Mirror the test class as `CodeEdgeConfidenceTest`. Same builder semantics.

- [ ] **Step 1: Read current `CodeEdge.java`**
- [ ] **Step 2: Write failing test (`CodeEdgeConfidenceTest`)** — mirror Task 2's three test cases on `CodeEdge.builder()`.
- [ ] **Step 3: Run + see failure.**
- [ ] **Step 4: Add fields + builder methods.**
- [ ] **Step 5: Run all model tests.**
- [ ] **Step 6: Commit:** `feat(model): add confidence + source to CodeEdge`.

---

### Task 4: Round-trip `confidence` + `source` through Neo4j (write path)

**Files:**
- Modify: `src/main/java/io/github/randomcodespace/iq/graph/GraphStore.java`
- Test: `src/test/java/io/github/randomcodespace/iq/graph/GraphStoreConfidenceRoundTripTest.java` (new)

- [ ] **Step 1: Write the failing test.**

```java
// src/test/java/io/github/randomcodespace/iq/graph/GraphStoreConfidenceRoundTripTest.java
package io.github.randomcodespace.iq.graph;

import io.github.randomcodespace.iq.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GraphStoreConfidenceRoundTripTest {

    @TempDir Path tmp;
    GraphStore store;

    @BeforeEach void setup() { store = GraphStore.openEmbedded(tmp.resolve("graph.db")); }
    @AfterEach  void close() { store.close(); }

    @Test
    void confidenceAndSourceRoundTrip() {
        CodeNode in = CodeNode.builder()
                .id("node:Foo.java:class:Foo")
                .kind(NodeKind.CLASS).label("Foo")
                .confidence(Confidence.RESOLVED).source("SpringServiceDetector")
                .build();
        store.bulkSave(List.of(in), List.of());

        CodeNode out = store.findById("node:Foo.java:class:Foo").orElseThrow();
        assertEquals(Confidence.RESOLVED, out.confidence());
        assertEquals("SpringServiceDetector", out.source());
    }
}
```

- [ ] **Step 2: Run; verify compile or assertion fail.**

```bash
mvn test -Dtest=GraphStoreConfidenceRoundTripTest -Dfrontend.skip=true -Ddependency-check.skip=true -q
```

Expected: assertion fails (fields written via existing path don't include confidence/source).

- [ ] **Step 3: Update `GraphStore.bulkSave` to write `prop_confidence` and `prop_source`**, and `nodeFromNeo4j` / `edgeFromNeo4j` to read them. Defaults if missing in Neo4j: `Confidence.LEXICAL` and `"unknown"`.

- [ ] **Step 4: Run round-trip test; verify pass.**
- [ ] **Step 5: Run wider GraphStore test suite to ensure no regression.**

```bash
mvn test -Dtest='io.github.randomcodespace.iq.graph.*' -Dfrontend.skip=true -Ddependency-check.skip=true -q
```

- [ ] **Step 6: Commit:** `feat(graph): round-trip confidence + source through Neo4j`.

---

### Task 5: H2 cache schema migration to v5

**Files:**
- Modify: `src/main/java/io/github/randomcodespace/iq/cache/AnalysisCache.java`
- Test: existing `AnalysisCacheTest.java` (extend) + new round-trip case.

- [ ] **Step 1: Failing test.** Add `confidence` and `source` columns to the SCHEMA_SQL `nodes` and `edges` tables. Failing assertion: `cache.put(file, [node with confidence=RESOLVED]); cache.get(file).confidence == RESOLVED`.

- [ ] **Step 2: Run; see fail.**
- [ ] **Step 3: Bump `CACHE_VERSION` 4→5. Add columns. Update INSERT/SELECT statements. Update Jackson serialization helpers if used.**
- [ ] **Step 4: Run cache tests; verify all pass.**
- [ ] **Step 5: Commit:** `feat(cache): bump CACHE_VERSION to 5; add confidence + source columns`.

---

### Task 6: Default `Confidence` per detector base class

**Files:**
- Modify: `AbstractRegexDetector.java`, `AbstractJavaParserDetector.java`, `AbstractAntlrDetector.java`, `AbstractStructuredDetector.java`, `AbstractPythonAntlrDetector.java`, `AbstractTypeScriptDetector.java`, `AbstractJavaMessagingDetector.java`, `AbstractPythonDbDetector.java`.
- Test: a synthetic `BaseClassConfidenceDefaultTest.java` per base class (or a single parameterized test).

- [ ] **Step 1: Failing parameterized test.** Subclass each base, emit a node with no explicit confidence, assert it carries the expected default (LEXICAL for regex, SYNTACTIC for AST/ANTLR/structured/python-antlr/typescript/messaging/python-db).
- [ ] **Step 2: Run; see fail (currently always LEXICAL or null).**
- [ ] **Step 3: Add a `defaultConfidence()` method on each base class returning the matching enum. Make `addNode`/`addEdge` helpers stamp it when not explicitly set.**
- [ ] **Step 4: Run; verify pass.**
- [ ] **Step 5: Run full detector suite to ensure no regression.**

```bash
mvn test -Dtest='io.github.randomcodespace.iq.detector.*' -Dfrontend.skip=true -Ddependency-check.skip=true -q
```

- [ ] **Step 6: Commit:** `feat(detector): set Confidence default per base class`.

---

### Task 7: Snapshot-test refresh (one-time)

JSON-snapshot or golden-file tests will now include the additive `confidence` and `source` fields. Acceptance criterion §13 #3 in the spec requires the diff is limited to those two fields per record.

- [ ] **Step 1: Run full test suite, capture failures.**

```bash
mvn test -Dfrontend.skip=true -Ddependency-check.skip=true -q -DfailIfNoTests=false 2>&1 | tee /tmp/snapshot-failures.log
```

- [ ] **Step 2: For each snapshot diff, verify the diff is only the two additive fields.** If anything else changed, that's a bug — fix it before refreshing the snapshot.

- [ ] **Step 3: Refresh snapshots one file at a time with separate commits per file** (so reviewers can diff cleanly).

- [ ] **Step 4: Run full suite; expect green.**
- [ ] **Step 5: Commit each snapshot refresh:** `chore(test): refresh <ScenarioName> snapshot for confidence + source fields`.

---

## Phase 2 — SPI scaffolding (Tasks 8–13)

### Task 8: `Resolved` interface + `EmptyResolved` singleton

**Files:**
- Create: `intelligence/resolver/Resolved.java`, `intelligence/resolver/EmptyResolved.java`
- Test: `ResolvedContractTest.java`

- [ ] **Step 1: Failing test.**

```java
// src/test/java/io/github/randomcodespace/iq/intelligence/resolver/ResolvedContractTest.java
package io.github.randomcodespace.iq.intelligence.resolver;

import io.github.randomcodespace.iq.model.Confidence;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResolvedContractTest {

    @Test
    void emptyResolvedIsSingleton() {
        assertSame(EmptyResolved.INSTANCE, EmptyResolved.INSTANCE);
    }

    @Test
    void emptyResolvedHasLexicalConfidence() {
        assertEquals(Confidence.LEXICAL, EmptyResolved.INSTANCE.sourceConfidence());
    }

    @Test
    void emptyResolvedReportsUnsupported() {
        assertFalse(EmptyResolved.INSTANCE.isAvailable());
    }
}
```

- [ ] **Step 2: Run; see fail.**
- [ ] **Step 3: Implement** `Resolved` (interface with `boolean isAvailable()`, `Confidence sourceConfidence()`, plus language-specific extension points to be added by `JavaResolved`) and `EmptyResolved.INSTANCE` (always returns `false` / `LEXICAL`).
- [ ] **Step 4: Run; pass.**
- [ ] **Step 5: Commit:** `feat(resolver): add Resolved interface + EmptyResolved singleton`.

---

### Task 9: `ResolutionException`

- [ ] **Step 1: Failing test:** assert `ResolutionException` carries the file path and language fields.
- [ ] **Step 2: Run; see fail.**
- [ ] **Step 3: Implement** as a checked exception (subclass `Exception`) with `Path file()`, `String language()`.
- [ ] **Step 4: Pass.**
- [ ] **Step 5: Commit:** `feat(resolver): add ResolutionException`.

---

### Task 10: `SymbolResolver` interface

```java
// src/main/java/io/github/randomcodespace/iq/intelligence/resolver/SymbolResolver.java
package io.github.randomcodespace.iq.intelligence.resolver;

import io.github.randomcodespace.iq.analyzer.DiscoveredFile;
import java.nio.file.Path;
import java.util.Set;

public interface SymbolResolver {
    Set<String> getSupportedLanguages();
    void bootstrap(Path projectRoot) throws ResolutionException;
    Resolved resolve(DiscoveredFile file, Object parsedAst) throws ResolutionException;
    default void shutdown() {}
}
```

- [ ] **Step 1: Failing contract test** — assert any concrete implementation (start with a stub) honors `getSupportedLanguages()` returning a non-empty `Set` and `resolve(...)` returning non-null.
- [ ] **Step 2: Run; see fail.**
- [ ] **Step 3: Implement** the interface as shown.
- [ ] **Step 4: Pass.**
- [ ] **Step 5: Commit:** `feat(resolver): add SymbolResolver SPI`.

---

### Task 11: `ResolverRegistry` Spring bean

**Files:**
- Create: `intelligence/resolver/ResolverRegistry.java`
- Test: `ResolverRegistryTest.java`

- [ ] **Step 1: Failing test.** Two `@Component` stub resolvers (`JavaStubResolver` for `"java"`, `TsStubResolver` for `"typescript"`). Wire via `@SpringBootTest(classes=...)`. Assert `registry.resolverFor("java")` is the Java stub; unknown language returns a no-op (returns `EmptyResolved`); `bootstrap(rootPath)` calls bootstrap on every registered resolver exactly once.

- [ ] **Step 2: Run; see fail.**

- [ ] **Step 3: Implement** `ResolverRegistry` as a `@Component` that takes `List<SymbolResolver>` via constructor injection, builds a `Map<String, SymbolResolver>` keyed by lowercase language. `resolverFor(String language)` returns matching or a default that emits `EmptyResolved`. `bootstrap(rootPath)` iterates resolvers in alphabetical order by class simple name (determinism), calling each.

- [ ] **Step 4: Pass.**

- [ ] **Step 5: Commit:** `feat(resolver): add ResolverRegistry with auto-discovery`.

---

### Task 12: `DetectorContext.resolved()` accessor

**Files:**
- Modify: `detector/DetectorContext.java`
- Test: existing `DetectorContextTest.java` (or new) + assertion that legacy detectors still compile.

- [ ] **Step 1: Failing test.** Build a `DetectorContext` with `.resolved(EmptyResolved.INSTANCE)`; assert the accessor returns it. Also assert default returns `Optional.empty()`.

- [ ] **Step 2: Run; see fail.**

- [ ] **Step 3: Add field + builder method + accessor**, additive (default `Optional.empty()`).

- [ ] **Step 4: Run all detector tests** to confirm legacy detectors still compile and behave identically.

```bash
mvn test -Dtest='io.github.randomcodespace.iq.detector.*' -Dfrontend.skip=true -Ddependency-check.skip=true -q
```

- [ ] **Step 5: Commit:** `feat(detector): add Optional<Resolved> accessor to DetectorContext`.

---

### Task 13: Sanity build

- [ ] **Step 1: Compile + run all model + resolver + detector tests.**

```bash
mvn test -Dtest='io.github.randomcodespace.iq.{model,intelligence.resolver,detector}.*' \
  -Dfrontend.skip=true -Ddependency-check.skip=true -q
```

- [ ] **Step 2: Confirm green; if not, fix the smallest possible failure before moving on.**

- [ ] **Step 3: Commit (only if any cleanup landed):** `chore: sanity build after Phase 2`.

---

## Phase 3 — Java backend (Tasks 14–18)

### Task 14: Add `javaparser-symbol-solver-core` dep

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Resolve the latest stable version compatible with `javaparser-core` 3.28.0.** Use `context7` MCP first; fall back to Maven Central via `ctx_fetch_and_index`.

- [ ] **Step 2: Add the dependency** to the `<dependencies>` block in `pom.xml`. Pin the version explicitly. Note: JavaParser publishes both core and symbol-solver from the same release train — they should share the same version.

```xml
<dependency>
    <groupId>com.github.javaparser</groupId>
    <artifactId>javaparser-symbol-solver-core</artifactId>
    <version>${javaparser.version}</version>
</dependency>
```

(Add a `<javaparser.version>3.28.0</javaparser.version>` property if not already present; reuse the existing version everywhere.)

- [ ] **Step 3: Run dependency check.**

```bash
mvn dependency:tree -Dincludes=com.github.javaparser -Dfrontend.skip=true -Ddependency-check.skip=true
```

Expected: `javaparser-core` and `javaparser-symbol-solver-core` both at the pinned version.

- [ ] **Step 4: Verify license** is Apache-2.0 (it is, but check `mvn dependency:tree` doesn't pull GPL/AGPL transitives).

- [ ] **Step 5: Compile.**

```bash
mvn test-compile -Dfrontend.skip=true -Ddependency-check.skip=true -q
```

- [ ] **Step 6: Commit:** `chore(deps): add javaparser-symbol-solver-core <version>`.

---

### Task 15: `JavaSourceRootDiscovery`

**Files:**
- Create: `intelligence/resolver/java/JavaSourceRootDiscovery.java`
- Test: `JavaSourceRootDiscoveryTest.java` with synthetic dir layouts via `@TempDir`.

- [ ] **Step 1: Failing test.** Cover:
  - Maven single-module: `<root>/pom.xml`, `src/main/java`, `src/test/java` → returns sorted `[src/main/java, src/test/java]`.
  - Maven multi-module: root `pom.xml` with `<module>service-a</module>` + `<module>service-b</module>`; each has `src/main/java`. Returns sorted union.
  - Gradle (`build.gradle.kts` or `build.gradle`): same `src/main/java` convention.
  - Plain layout: just `src/` without Maven/Gradle markers — returns `[src/]` if it has `*.java`.
  - Empty project (no Java): returns empty list, no exception.
  - Symlink loop in tree: terminates without exception.

```java
@Test void mavenSingleModule(@TempDir Path tmp) throws Exception {
    Files.createDirectories(tmp.resolve("src/main/java"));
    Files.createDirectories(tmp.resolve("src/test/java"));
    Files.writeString(tmp.resolve("pom.xml"), "<project/>");
    var roots = new JavaSourceRootDiscovery().discover(tmp);
    assertEquals(List.of(tmp.resolve("src/main/java"), tmp.resolve("src/test/java")), roots);
}
```

- [ ] **Step 2: Run; see fail.**
- [ ] **Step 3: Implement** discovery using `Files.walk` with depth limits. Return `List<Path>` sorted alphabetically. Idempotent.
- [ ] **Step 4: Run all 6+ scenarios; verify pass.**
- [ ] **Step 5: Commit:** `feat(resolver/java): add JavaSourceRootDiscovery (Maven/Gradle/plain auto-detect)`.

---

### Task 16: `JavaResolved` record

**Files:**
- Create: `intelligence/resolver/java/JavaResolved.java`
- Test: `JavaResolvedTest.java`

- [ ] **Step 1: Failing test.** Construct a `JavaResolved` with a stub `JavaSymbolSolver` and a parsed `CompilationUnit`. Assert `isAvailable() == true`, `sourceConfidence() == RESOLVED`, exposes `.cu()` and `.solver()`.

- [ ] **Step 2: Run; see fail.**

- [ ] **Step 3: Implement** as a `record JavaResolved(CompilationUnit cu, JavaSymbolSolver solver) implements Resolved`. `isAvailable() = true`. `sourceConfidence() = Confidence.RESOLVED`.

- [ ] **Step 4: Pass.**

- [ ] **Step 5: Commit:** `feat(resolver/java): add JavaResolved record`.

---

### Task 17: `JavaSymbolResolver` (`@Component`)

**Files:**
- Create: `intelligence/resolver/java/JavaSymbolResolver.java`
- Test: covered by Task 18 (unit tests) and Task 30+ (aggressive layers).

- [ ] **Step 1: Failing skeleton test.**

```java
@Test void supportsJava() {
    var r = new JavaSymbolResolver(new JavaSourceRootDiscovery());
    assertEquals(Set.of("java"), r.getSupportedLanguages());
}

@Test void bootstrapBuildsCombinedTypeSolver(@TempDir Path tmp) throws Exception {
    Files.createDirectories(tmp.resolve("src/main/java"));
    Files.writeString(tmp.resolve("pom.xml"), "<project/>");
    var r = new JavaSymbolResolver(new JavaSourceRootDiscovery());
    r.bootstrap(tmp);
    assertNotNull(r.combinedTypeSolver());
}
```

- [ ] **Step 2: Run; see fail.**

- [ ] **Step 3: Implement.**

```java
@Component
public class JavaSymbolResolver implements SymbolResolver {
    private final JavaSourceRootDiscovery discovery;
    private CombinedTypeSolver combined;
    private JavaSymbolSolver solver;

    public JavaSymbolResolver(JavaSourceRootDiscovery discovery) {
        this.discovery = discovery;
    }

    @Override public Set<String> getSupportedLanguages() { return Set.of("java"); }

    @Override
    public void bootstrap(Path projectRoot) throws ResolutionException {
        try {
            CombinedTypeSolver cts = new CombinedTypeSolver();
            cts.add(new ReflectionTypeSolver());
            for (Path root : discovery.discover(projectRoot)) {
                cts.add(new JavaParserTypeSolver(root.toFile()));
            }
            this.combined = cts;
            this.solver = new JavaSymbolSolver(cts);
            // Configure JavaParser default ParserConfiguration so any subsequent parse
            // benefits from the solver — but allow per-parse override for tests.
            StaticJavaParser.getParserConfiguration().setSymbolResolver(this.solver);
        } catch (Exception e) {
            throw new ResolutionException("bootstrap failed for " + projectRoot, e, projectRoot, "java");
        }
    }

    @Override
    public Resolved resolve(DiscoveredFile file, Object parsedAst) throws ResolutionException {
        if (!"java".equalsIgnoreCase(file.language())) return EmptyResolved.INSTANCE;
        if (!(parsedAst instanceof CompilationUnit cu)) return EmptyResolved.INSTANCE;
        if (this.solver == null) return EmptyResolved.INSTANCE;
        return new JavaResolved(cu, solver);
    }

    public CombinedTypeSolver combinedTypeSolver() { return combined; }
}
```

- [ ] **Step 4: Pass.**
- [ ] **Step 5: Commit:** `feat(resolver/java): add JavaSymbolResolver`.

---

### Task 18: `JavaSymbolResolverTest` — Layer 1 (resolver unit tests)

**Files:**
- Create: `JavaSymbolResolverTest.java`
- Create: synthetic Java sources under `src/test/resources/intelligence/resolver/java/<scenario>/`.

Cover all 15+ scenarios from spec §12 layer 1: empty file, single class, generics deep nesting, inner classes (static/non-static/anonymous/local), lambdas, records, sealed, enum-with-methods, interface-with-default, abstract, annotations, imports (explicit/static/wildcard/missing/unused), cyclic imports, same-named-classes-different-packages, JDK symbol, multi-source-root cross-reference.

- [ ] **Step 1: For each scenario, write the synthetic source file** under `src/test/resources/intelligence/resolver/java/<scenario>/Foo.java` (or multiple files where needed) with a `README.md` describing intent (one paragraph).

- [ ] **Step 2: Write the failing test class** (one `@Test` per scenario, named `resolves<Scenario>`).

- [ ] **Step 3: Run; see fail.**

- [ ] **Step 4: Verify fixtures alone are valid Java** by compiling them with `javac`; fix any syntax errors.

- [ ] **Step 5: Run resolver tests; iteratively fix any unexpected resolver behavior.**

- [ ] **Step 6: Commit (after each batch of ~5 scenarios passes):** `test(resolver/java): add Layer 1 scenarios <list>`.

---

## Phase 4 — Pipeline wiring (Tasks 19–21)

### Task 19: Wire `ResolverRegistry` into `Analyzer.run()`

- [ ] **Step 1: Failing test** (`AnalyzerResolverWiringTest`): assert `Analyzer.run(rootPath)` calls `registry.bootstrap(rootPath)` exactly once before any file is processed.

- [ ] **Step 2: Run; fail.**

- [ ] **Step 3: Inject `ResolverRegistry` into `Analyzer` (constructor injection, additive).** Add the bootstrap call at the top of `run()`. Order: discovery → resolver bootstrap → file iteration. (Discovery first so we know there's something to scan.)

- [ ] **Step 4: Pass.**

- [ ] **Step 5: Commit:** `feat(analyzer): bootstrap ResolverRegistry once per run`.

---

### Task 20: Wire per-file resolution into the file-iteration loop

- [ ] **Step 1: Failing test:** assert that for each file, `registry.resolverFor(file.language()).resolve(...)` is called and the returned `Resolved` is set on the `DetectorContext`.

- [ ] **Step 2: Fail.**

- [ ] **Step 3: Update the file-iteration block in `Analyzer`** to call `registry.resolverFor(file.language()).resolve(file, parsedAst)` and stuff the result into `DetectorContext.builder().resolved(...)`. Catch `ResolutionException` per file (log DEBUG, fall back to `EmptyResolved`).

- [ ] **Step 4: Pass.**

- [ ] **Step 5: Commit:** `feat(analyzer): per-file symbol resolution wired into pipeline`.

---

### Task 21: Mirror in `IndexCommand`

`IndexCommand` has its own batched H2 pipeline that's not entirely shared with `Analyzer`. Mirror the resolver bootstrap + per-file resolve path there.

- [ ] **Step 1: Failing test** (`IndexCommandResolverWiringTest`).
- [ ] **Step 2: Fail.**
- [ ] **Step 3: Update `IndexCommand` similarly** — same constructor injection of `ResolverRegistry`, same call shape.
- [ ] **Step 4: Pass.**
- [ ] **Step 5: Commit:** `feat(cli): wire ResolverRegistry into IndexCommand`.

---

## Phase 5 — Configuration (Tasks 22–23)

### Task 22: `intelligence.symbol_resolution.java.*` config keys

- [ ] **Step 1: Failing test** (`UnifiedConfigResolverKeysTest`): assert config object after parsing the example YAML carries `enabled = true`, `sourceRoots = "auto"`, `jdkReflection = true`, `bootstrapTimeoutSeconds = 30`, `maxPerFileResolveMs = 500`.

- [ ] **Step 2: Fail.**

- [ ] **Step 3: Add the new section + binding code** in unified config + `CodeIqConfig` legacy bridge (per `UnifiedConfigBeans`).

- [ ] **Step 4: Pass.**

- [ ] **Step 5: Commit:** `feat(config): add intelligence.symbol_resolution.java.* keys`.

---

### Task 23: Document the keys in `docs/codeiq.yml.example`

- [ ] **Step 1: Add the YAML block** matching spec §7 verbatim.
- [ ] **Step 2: Run `codeiq config validate`** against the example file (after building the JAR if needed) to confirm it parses.
- [ ] **Step 3: Commit:** `docs(config): document intelligence.symbol_resolution.java.* keys`.

---

## Phase 6 — Detector migration (Tasks 24–29)

Each migration follows the same TDD pattern. Concrete code differs per detector, but the test scaffolding is identical.

### Task pattern (apply to each detector below)

For detector `<X>Detector`:

- [ ] **Step 1: Read current detector + test** so you have the existing edge logic in context.

```bash
sed -n '1,200p' src/main/java/io/github/randomcodespace/iq/detector/jvm/java/<X>Detector.java
```

- [ ] **Step 2: Add three new test methods to `<X>DetectorTest`:**
  - `resolvedModeProducesResolvedEdge` — feed a fixture where the receiver type would be ambiguous lexically; with resolved context, assert edge target is the *correct* node ID.
  - `fallbackModeMatchesPreSpecBaseline` — `ctx.resolved() == Optional.empty()`; assert logical-content output identical to the baseline (modulo additive fields).
  - `mixedModeUsesResolverWhereAvailable` — half the files have resolved context, half don't; assert per-file confidence labelling.

- [ ] **Step 3: Run; see fails.**

- [ ] **Step 4: Update the detector to:**
  - Accept `ctx.resolved()` as `Optional<Resolved>`.
  - When present and is `JavaResolved`, use `solver` to resolve receiver types / generic args / referenced classes for the specific edges relevant to this detector.
  - Stamp `Confidence.RESOLVED` on resolved-mode edges; existing path stamps base-class default.

- [ ] **Step 5: Run all `<X>DetectorTest`; verify pass + no regression.**

- [ ] **Step 6: Run determinism case** (run detector twice on same input, assert byte-identical output).

- [ ] **Step 7: Commit:** `feat(detector/<X>): use resolved symbol info for <specific edge type>`.

### Task 24: `SpringServiceDetector` migration

- Resolves `@Autowired UserService userService` to the actual `UserService` class node ID.
- Edge: `INJECTS` from the consumer class to the declared `UserService` type.
- Fixture: two `UserService` classes in different packages; assert resolution picks the imported one.

### Task 25: `SpringRepositoryDetector` migration

- Resolves the entity type parameter on `JpaRepository<T, ID>`.
- Edge: `MAPS_TO` from repository interface to the resolved entity class.

### Task 26: `JpaEntityDetector` migration

- Resolves generic args on `@OneToMany List<Owner>`.
- Edge: `MAPS_TO` between entities (the holder and the related entity).

### Task 27: `JpaRepositoryDetector` migration

- Same as Spring repo, deeper. Resolves derived-query method-name return types where applicable (less reliable; flag as `Confidence.SYNTACTIC` if resolution is partial).

### Task 28: `KafkaListenerDetector` migration

- Resolves `@KafkaListener(topics = TOPIC_CONST)` where `TOPIC_CONST` is a static field — produce edges to the resolved topic name.
- Edge: `LISTENS` to the topic node.

### Task 29: `SpringRestDetector` migration

- Resolves `@RequestBody UserDto dto` and `@PathVariable` types.
- Edge: `MAPS_TO` from endpoint node to the resolved DTO class.

---

## Phase 7 — Aggressive testing layers (Tasks 30–38)

### Task 30: Layer 6 — Determinism (resolver-stage)

**Files:**
- Create: `JavaSymbolResolverDeterminismTest.java`

- [ ] **Step 1: Failing test.** Run the resolver twice against the same fixture; assert byte-identical serialized `Resolved` output (use Jackson with stable ordering).

- [ ] **Step 2: Fail.**

- [ ] **Step 3: Confirm resolver implementation already sorts source roots, uses `TreeMap` etc. — fix if not.**

- [ ] **Step 4: Pass.**

- [ ] **Step 5: Add the second variant: source roots passed in different order, same output.**

- [ ] **Step 6: Commit:** `test(resolver/java): determinism — Layer 6`.

---

### Task 31: Layer 3 — Concurrency stress

**Files:**
- Create: `JavaSymbolResolverConcurrencyTest.java`

- [ ] **Step 1: Generate 1000 synthetic Java files** in `@TempDir` (one class each, distinct names). Single source root.

- [ ] **Step 2: Failing test:** resolve all 1000 files via virtual-thread fan-out; assert no exceptions, no duplicate node IDs in the union of `Resolved` outputs, total time within 2× the sequential baseline.

- [ ] **Step 3: Fail/pass.** If fail, investigate (likely: bootstrap not idempotent under concurrent first-call). Add a `synchronized`/`volatile` initialization guard.

- [ ] **Step 4: Add invocation-count test** — bootstrap is called exactly once even under N concurrent first-callers.

- [ ] **Step 5: Commit:** `test(resolver/java): concurrency stress — Layer 3`.

---

### Task 32: Layer 4 — Memory / pathological

**Files:**
- Create: `JavaSymbolResolverPathologicalTest.java`

- [ ] **Step 1: Generate fixtures** (synthesizable in setup):
  - 10K-line class with mostly trivial methods.
  - File with 1000 imports (most unresolvable).
  - 10-deep generic nesting.

- [ ] **Step 2: Failing tests under `-Xmx512m`** (set via Surefire config in pom).

- [ ] **Step 3: Run; pass or fix.** Likely passes; if not, investigate JavaSymbolSolver's caching footprint.

- [ ] **Step 4: Add timeout assertion** — each pathological case completes within `max_per_file_resolve_ms`.

- [ ] **Step 5: Commit:** `test(resolver/java): pathological inputs — Layer 4`.

---

### Task 33: Layer 5 — Adversarial

- [ ] **Step 1:** Cover the spec §12 layer 5 cases: syntax-error file, mis-tagged language, mixed source root, ReflectionTypeSolver disabled (config flag).
- [ ] **Step 2:** Run; fix.
- [ ] **Step 3: Commit:** `test(resolver/java): adversarial inputs — Layer 5`.

---

### Task 34: Layer 7 — E2E petclinic regression

**Files:**
- Modify: existing `E2EQualityTest` (extend) or create `E2EQualityResolverTest`.

- [ ] **Step 1: Capture baseline numbers.** Run `E2EQualityTest` with `intelligence.symbol_resolution.java.enabled=false`. Record edge precision/recall against `src/test/resources/e2e/ground-truth-petclinic.json`. Save to a baseline JSON checked into the test resources.

- [ ] **Step 2: Run with `enabled=true`. Record post-change numbers.**

- [ ] **Step 3: Failing assertion:** `precision_after > precision_before AND recall_after >= recall_before` (improvement on at least one, no regression on the other).

- [ ] **Step 4: If precision/recall didn't move: investigate why.** Likely the migrated detectors aren't producing the expected resolved edges yet — go back to Phase 6 and fix.

- [ ] **Step 5: Commit:** `test(e2e): petclinic resolver-mode improvement gate — Layer 7`.

---

### Task 35: Layer 8 — Property-based (jqwik) — license check first

- [ ] **Step 1: License check.** jqwik is EPL-2.0. Per `~/.claude/rules/dependencies.md` it's not on the preferred (MIT/Apache/BSD) list. **Ask the user explicitly before adding.** If declined, write hand-rolled randomized generators using existing JUnit + `java.util.Random` instead.

- [ ] **Step 2: If approved, add jqwik to `pom.xml`** at test scope. Resolve latest stable via `context7`.

- [ ] **Step 3: Failing properties:**
  - `forall valid_java_source: resolver does not throw unchecked` (only `ResolutionException`).
  - `forall valid_java_source: resolver terminates within max_per_file_resolve_ms`.
  - `forall valid_java_source × file_in_unrelated_root: editing file_in_unrelated_root does not change resolution of valid_java_source`.

- [ ] **Step 4: Run; iterate.**

- [ ] **Step 5: Commit:** `test(resolver/java): property-based — Layer 8`.

---

### Task 36: Layer 9 — PIT mutation testing (non-gating profile)

- [ ] **Step 1: Add PIT plugin to `pom.xml` under a non-default profile** `mutation`.

```xml
<profile>
  <id>mutation</id>
  <build>
    <plugins>
      <plugin>
        <groupId>org.pitest</groupId>
        <artifactId>pitest-maven</artifactId>
        <version>1.18.0</version> <!-- resolve via context7 -->
        <configuration>
          <targetClasses>
            <param>io.github.randomcodespace.iq.intelligence.resolver.*</param>
            <param>io.github.randomcodespace.iq.model.Confidence</param>
          </targetClasses>
        </configuration>
      </plugin>
    </plugins>
  </build>
</profile>
```

- [ ] **Step 2: Run** `mvn -P mutation pitest:mutationCoverage -Dfrontend.skip=true -Ddependency-check.skip=true`.

- [ ] **Step 3: Inspect the mutation kill rate.** Target ≥ 80% on the new packages. If lower, add focused tests until the rate clears 80%.

- [ ] **Step 4: Commit:** `test(resolver): mutation testing profile (PIT) — Layer 9`.

---

### Task 37: Aggregate test gate

- [ ] **Step 1: Run full `mvn test` with both config states.**

```bash
# enabled=false
CODEIQ_INTELLIGENCE_SYMBOL_RESOLUTION_JAVA_ENABLED=false \
  mvn test -Dfrontend.skip=true -Ddependency-check.skip=true

# enabled=true (default)
mvn test -Dfrontend.skip=true -Ddependency-check.skip=true
```

- [ ] **Step 2: Fix any unexpected failure.**

- [ ] **Step 3: Run `mvn verify` for the security gate** (this downloads NVD on first run — allow ~10 min).

```bash
mvn verify -Dfrontend.skip=true
```

- [ ] **Step 4: Commit:** `test: aggregate gate green for sub-project 1`.

---

### Task 38: Performance gate

- [ ] **Step 1: Time `index` against `spring-petclinic`.**

```bash
time java -jar target/code-iq-*-cli.jar index $E2E_PETCLINIC_DIR
```

Compare to the pre-change baseline (run on `main` once, before this branch's first impl commit landed). Acceptance: bootstrap < 10 s; per-Java-file resolve median ≤ 200 ms; total Java analysis time ≤ +60% of baseline.

- [ ] **Step 2: If exceeded, profile** with `async-profiler` or VisualVM. Fix the regression. (Spec §9 documents the budget; exceeding it without justification is a bug.)

- [ ] **Step 3: Record numbers in PR description.**

- [ ] **Step 4: No commit needed unless a fix landed.**

---

## Phase 8 — Doc updates + PR (Tasks 39–42)

### Task 39: Expand `CHANGELOG.md` `[Unreleased]` entry

- [ ] **Step 1: Add an `### Added` bullet** under `[Unreleased]` describing the resolver SPI, Java pilot, confidence/provenance schema, cache-version bump, migrated detectors. Cross-reference the spec at `docs/specs/2026-04-27-resolver-spi-and-java-pilot-design.md`.

- [ ] **Step 2: Add a `### Changed` bullet** noting `CACHE_VERSION` 4 → 5 (one-time cache rebuild on first run after upgrade).

- [ ] **Step 3: Commit:** `docs(changelog): add sub-project 1 entry`.

---

### Task 40: `CLAUDE.md` Gotchas update

- [ ] **Step 1: Add bullets:**
  - Confidence + source are now mandatory on every node/edge — base classes set defaults; detectors override to `RESOLVED` when consuming `ctx.resolved()`.
  - The pipeline now has a resolve stage between parse and detect. Profile selection unchanged.
  - `CACHE_VERSION` is 5 — bumping invalidates all existing `.codeiq/cache/` dirs on first run.
  - `intelligence.symbol_resolution.java.enabled=false` is the off-switch for raw-speed scans or backward-compat snapshots.

- [ ] **Step 2: Commit:** `docs(claude): gotchas for sub-project 1`.

---

### Task 41: `PROJECT_SUMMARY.md` updates

- [ ] **Step 1: Tech-stack row addition:** `| AST + symbols | JavaParser 3.28.0 + javaparser-symbol-solver-core | pom.xml |`.

- [ ] **Step 2: Gotchas updates:** mention `Confidence`, the resolve stage, the `CACHE_VERSION` bump.

- [ ] **Step 3: Commit:** `docs(summary): note resolver pipeline + Confidence schema`.

---

### Task 42: Push branch + open PR

- [ ] **Step 1: Push branch** to `origin`.

```bash
git push -u origin feat/sub-project-1-resolver-spi-and-java-pilot
```

- [ ] **Step 2: Open PR via `gh`.**

```bash
gh pr create --title "feat: sub-project 1 — resolver SPI + Java pilot + confidence schema" \
  --body "$(cat <<'EOF'
## Summary
- Symbol-resolution stage between parse and detect, per-language `SymbolResolver` SPI auto-discovered as Spring `@Component`s.
- Java backend wraps JavaParser's `JavaSymbolSolver` (no new dependency tree — same release train as `javaparser-core`).
- `Confidence` enum (`LEXICAL`/`SYNTACTIC`/`RESOLVED`) and `source` field on every `CodeNode` / `CodeEdge`, round-tripped through Neo4j (`prop_*` convention) and H2 cache (schema v5).
- 4–6 Java detectors migrated as proof of value (Spring service / repository, JPA entity / repo, Kafka listener, Spring REST).
- 9 layers of aggressive testing (unit, integration, concurrency, pathological, adversarial, determinism, E2E petclinic regression, property-based via jqwik [pending license OK], PIT mutation profile).

## Spec
[`docs/specs/2026-04-27-resolver-spi-and-java-pilot-design.md`](docs/specs/2026-04-27-resolver-spi-and-java-pilot-design.md)

## Acceptance criteria
See spec §13. All checked.

## Test plan
- [x] `mvn verify` green on CI
- [x] No logical-content regression with `enabled: false` (snapshots refreshed in separate commits — see history)
- [x] E2E petclinic precision / recall measurably up with `enabled: true` (numbers below)
- [x] Determinism gate: resolver runs byte-identical 10× on same input
- [x] Concurrency stress: 1000 files via virtual threads, no deadlocks
- [x] Layer 8 jqwik / Layer 9 PIT non-gating signals captured in the PR

## Petclinic numbers
| Metric | enabled=false (baseline) | enabled=true (this PR) | Δ |
|---|---|---|---|
| edge precision | _filled at impl time_ | _filled at impl time_ | + |
| edge recall | _filled at impl time_ | _filled at impl time_ | + |

## Out of scope
- Sub-projects 2–8 (TS / Python / Go / Rust+C+++C# resolvers, framework-aware detect refactor, FP harness, MCP read-path hardening). Each gets its own spec → plan → impl cycle.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Wait for CI;** if any failure, fix on the branch and push (do not `--amend` and force-push). Repeat until CI green.

- [ ] **Step 4: Hand back to user** per default check-in cadence (b): "PR is open, tests green, ready for human review."

---

## Self-review (run after writing the plan, before execution)

1. **Spec coverage** — every acceptance criterion (§13) maps to at least one task. Verified.
2. **Placeholder scan** — no "TBD"/"TODO"/"figure out"; concrete code blocks for foundational tasks; templated patterns for repeated migrations. Acceptable per skill DRY guidance.
3. **Type / naming consistency** — `Confidence`, `Resolved`, `EmptyResolved`, `SymbolResolver`, `ResolverRegistry`, `JavaSymbolResolver`, `JavaResolved`, `JavaSourceRootDiscovery` — all referenced consistently across tasks.
4. **Backward compatibility** — Phase 6 detectors keep their existing logic; resolver consumption is purely additive.
5. **Determinism** — Tasks 30, 31 (concurrency), and detector determinism (per Task pattern Step 6) all preserve the determinism gate.
6. **Performance budget** — Task 38 explicitly checks the spec §9 numbers.
7. **License decisions** — Task 35 (jqwik) is gated on user approval; Task 36 (PIT) is Apache-2.0, fine.
8. **Test refresh hazard** — Task 7 isolates the snapshot refresh into its own commit chain so reviewers can verify the diff is bounded to the additive fields.
